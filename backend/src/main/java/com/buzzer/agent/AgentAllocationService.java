package com.buzzer.agent;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.notification.NotificationService;
import com.buzzer.ops.OpsExceptionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated-agent allocation.
 * Ranking: existing relationship > not on leave > same city > locality coverage > speaks the customer's first-choice
 * language > number of the customer's languages spoken > availability > lowest relative workload (active customers / capacity). Managers are used only when no
 * regular agent is available. A backup agent is allocated alongside the primary.
 */
@Service
public class AgentAllocationService {
    public record Candidate(UUID id, UUID userId, String displayName, double loadRatio) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final NotificationService notifications;
    private final OpsExceptionService ops;

    public AgentAllocationService(JdbcClient jdbc, AuditService audit, NotificationService notifications, OpsExceptionService ops) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
        this.ops = ops;
    }

    @Transactional
    public Optional<UUID> allocatePrimary(UUID customerUserId, UUID assignedBy, String reason) {
        Optional<UUID> existing = activeLink(customerUserId, "PRIMARY");
        if (existing.isPresent()) return existing;

        List<Candidate> ranked = rank(customerUserId, null);
        if (ranked.isEmpty()) {
            ops.raise("NO_AGENT_AVAILABLE", "HIGH", "No agent available for a new customer", "USER", customerUserId,
                    "Assign an agent manually", "no-agent:" + customerUserId, Map.of());
            return Optional.empty();
        }
        Candidate primary = ranked.getFirst();
        link(customerUserId, primary.id(), "PRIMARY", assignedBy, reason);
        ranked.stream().filter(c -> !c.id().equals(primary.id())).findFirst()
                .ifPresent(b -> link(customerUserId, b.id(), "BACKUP", assignedBy, reason));
        notifications.notifyUser(primary.userId(), "New customer assigned", "A new customer has been added to your portfolio.",
                "AGENT_PORTFOLIO", "USER", customerUserId);
        return Optional.of(primary.id());
    }

    /** Manager / admin override. Ends the existing link for that role and records the reason. */
    @Transactional
    public void reassign(UUID customerUserId, UUID agentId, String linkRole, UUID by, String reason) {
        if (!List.of("PRIMARY", "BACKUP", "TEMPORARY").contains(linkRole)) throw BusinessException.invalid("Invalid link role");
        Integer exists = jdbc.sql("SELECT count(*) FROM agents WHERE id = :a AND active").param("a", agentId).query(Integer.class).single();
        if (exists == 0) throw BusinessException.notFound("Agent");
        jdbc.sql("UPDATE customer_agent_links SET active = FALSE, ended_at = now() WHERE customer_user_id = :c AND link_role = :r AND active")
                .param("c", customerUserId).param("r", linkRole).update();
        // A customer must not have the same agent as both primary and backup.
        jdbc.sql("UPDATE customer_agent_links SET active = FALSE, ended_at = now() WHERE customer_user_id = :c AND agent_id = :a AND active")
                .param("c", customerUserId).param("a", agentId).update();
        link(customerUserId, agentId, linkRole, by, reason);
        audit.record(by, "MANAGER", "CUSTOMER_AGENT_REASSIGNED", "USER", customerUserId,
                Map.of("agentId", agentId.toString(), "role", linkRole, "reason", reason == null ? "" : reason));
        UUID agentUser = jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", agentId).query(UUID.class).single();
        notifications.notifyUser(agentUser, "Customer assigned to you", "A customer has been assigned to you as " + linkRole.toLowerCase() + " agent.",
                "AGENT_PORTFOLIO", "USER", customerUserId);
        notifications.notifyUser(customerUserId, "Your BUZZER agent has been updated", "We have updated your personal agent so you always have someone to help.",
                "AGENT_CHANGE", "USER", customerUserId);
    }

    public Optional<UUID> activeLink(UUID customerUserId, String role) {
        return jdbc.sql("SELECT agent_id FROM customer_agent_links WHERE customer_user_id = :c AND link_role = :r AND active")
                .param("c", customerUserId).param("r", role).query(UUID.class).optional();
    }

    /** On-duty agent for a single request from a customer without a dedicated agent ("Explore" customers). No link is created. */
    public Optional<Candidate> onDuty(UUID customerUserId) {
        return rank(customerUserId, null).stream().findFirst();
    }

    /** Best available agent other than {@code excludeAgentId}; used for escalations and backups. */
    public Optional<Candidate> bestAvailable(UUID customerUserId, UUID excludeAgentId) {
        return rank(customerUserId, excludeAgentId).stream().findFirst();
    }

    private List<Candidate> rank(UUID customerUserId, UUID exclude) {
        return jdbc.sql("""
                WITH cust AS (
                  SELECT cp.city_id, cp.locality_id,
                         lower(COALESCE(cp.agent_preferences->'languages'->>0, cp.preferred_language, u.preferred_language)) AS lang,
                         ARRAY(SELECT lower(x) FROM jsonb_array_elements_text(COALESCE(cp.agent_preferences->'languages', '[]'::jsonb)) x) AS langs
                  FROM customer_profiles cp JOIN users u ON u.id = cp.user_id WHERE cp.user_id = :c)
                SELECT a.id, a.user_id, a.display_name,
                       (SELECT count(*) FROM customer_agent_links l WHERE l.agent_id = a.id AND l.active)::float / GREATEST(a.max_customers, 1) AS load_ratio
                FROM agents a LEFT JOIN cust ON TRUE
                WHERE a.active AND a.availability <> 'ON_LEAVE' AND (CAST(:ex AS uuid) IS NULL OR a.id <> CAST(:ex AS uuid))
                  AND (cust.city_id IS NULL OR a.city_id = cust.city_id)
                  AND (SELECT count(*) FROM customer_agent_links l WHERE l.agent_id = a.id AND l.active) < a.max_customers
                ORDER BY
                  a.is_manager ASC,
                  (cust.locality_id IS NOT NULL AND EXISTS (SELECT 1 FROM agent_localities al WHERE al.agent_id = a.id AND al.locality_id = cust.locality_id)) DESC,
                  COALESCE(cust.lang = ANY(string_to_array(lower(replace(a.languages, ' ', '')), ',')), FALSE) DESC,
                  COALESCE(cardinality(ARRAY(SELECT unnest(cust.langs) INTERSECT SELECT unnest(string_to_array(lower(replace(a.languages, ' ', '')), ',')))), 0) DESC,
                  (a.availability = 'AVAILABLE') DESC,
                  load_ratio ASC, a.created_at ASC""")
                .param("c", customerUserId).param("ex", exclude).query(Candidate.class).list();
    }

    private void link(UUID customer, UUID agent, String role, UUID by, String reason) {
        jdbc.sql("""
                INSERT INTO customer_agent_links(customer_user_id, agent_id, link_role, assigned_by, reason)
                VALUES (:c, :a, :r, :b, :reason)""")
                .param("c", customer).param("a", agent).param("r", role).param("b", by).param("reason", reason).update();
    }
}
