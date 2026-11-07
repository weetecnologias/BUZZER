package com.buzzer.ops;

import com.buzzer.agent.AgentAllocationService;
import com.buzzer.common.AuditService;
import com.buzzer.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SLA and availability escalation.
 *  - If the primary agent has not responded before sla_due_at (configured per priority in sla_rules),
 *    the request is escalated to the customer's backup agent, else the best available agent, else the
 *    primary agent's manager, and an SLA_BREACH exception is raised.
 *  - When an agent goes OFFLINE / ON_LEAVE, their un-responded requests are escalated immediately.
 * First-response time and resolution time are tracked separately on the request.
 */
@Service
public class EscalationService {
    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private record Pending(UUID id, String requestNumber, UUID customerUserId, UUID agentId, String priority) {}

    private final JdbcClient jdbc;
    private final AgentAllocationService allocation;
    private final NotificationService notifications;
    private final OpsExceptionService ops;
    private final AuditService audit;

    public EscalationService(JdbcClient jdbc, AgentAllocationService allocation, NotificationService notifications,
                             OpsExceptionService ops, AuditService audit) {
        this.jdbc = jdbc;
        this.allocation = allocation;
        this.notifications = notifications;
        this.ops = ops;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${buzzer.escalation.poll-ms:60000}")
    public void scheduledSweep() {
        try {
            int n = escalateBreached();
            if (n > 0) log.info("Escalated {} request(s) past first-response SLA", n);
        } catch (Exception e) {
            log.warn("Escalation sweep failed: {}", e.getMessage());
        }
    }

    @Transactional
    public int escalateBreached() {
        List<Pending> due = jdbc.sql("""
                SELECT id, request_number, customer_user_id, agent_id, priority FROM service_requests
                WHERE status = 'REQUEST_CREATED' AND first_agent_response_at IS NULL AND escalated_at IS NULL AND sla_due_at < now()
                ORDER BY sla_due_at LIMIT 100 FOR UPDATE SKIP LOCKED""").query(Pending.class).list();
        for (Pending p : due) escalate(p, "SLA_BREACH", "First response SLA breached");
        return due.size();
    }

    /** Agent became unavailable: hand their un-responded requests to backup/manager now. */
    @Transactional
    public int escalateForUnavailableAgent(UUID agentId) {
        List<Pending> open = jdbc.sql("""
                SELECT id, request_number, customer_user_id, agent_id, priority FROM service_requests
                WHERE agent_id = :a AND first_agent_response_at IS NULL AND escalated_at IS NULL
                  AND status IN ('REQUEST_CREATED','AGENT_REVIEW') FOR UPDATE SKIP LOCKED""").param("a", agentId).query(Pending.class).list();
        for (Pending p : open) escalate(p, "AGENT_UNAVAILABLE", "Primary agent unavailable");
        return open.size();
    }

    private void escalate(Pending p, String type, String reason) {
        Optional<UUID> target = allocation.activeLink(p.customerUserId(), "BACKUP")
                .filter(this::isWorking)
                .filter(b -> !b.equals(p.agentId()))
                .or(() -> allocation.bestAvailable(p.customerUserId(), p.agentId()).map(AgentAllocationService.Candidate::id))
                .or(() -> p.agentId() == null ? Optional.empty()
                        : jdbc.sql("SELECT manager_id FROM agents WHERE id = :a AND manager_id IS NOT NULL").param("a", p.agentId())
                        .query(UUID.class).optional());
        jdbc.sql("UPDATE service_requests SET escalated_to_agent_id = :t, escalated_at = now() WHERE id = :id")
                .param("t", target.orElse(null)).param("id", p.id()).update();
        jdbc.sql("""
                INSERT INTO request_status_history(request_id, from_status, to_status, actor_role, reason)
                SELECT id, status, status, 'SYSTEM', :reason FROM service_requests WHERE id = :id""")
                .param("reason", "Escalated: " + reason).param("id", p.id()).update();
        audit.record(null, "SYSTEM", "REQUEST_ESCALATED", "REQUEST", p.id(), Map.of("type", type, "to", String.valueOf(target.orElse(null))));
        target.ifPresent(agentId -> {
            UUID user = jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", agentId).query(UUID.class).single();
            notifications.notifyUser(user, "Escalated: " + p.requestNumber(), reason + ". Please respond to the customer.",
                    "AGENT_ESCALATION", "REQUEST", p.id());
        });
        ops.raise(type, "URGENT".equals(p.priority()) || "HIGH".equals(p.priority()) ? "URGENT" : "HIGH",
                reason + " on " + p.requestNumber(), "REQUEST", p.id(),
                target.isPresent() ? "Escalated agent to respond" : "Assign an agent manually", "escalation:" + p.id(),
                Map.of("escalatedTo", String.valueOf(target.orElse(null))));
    }

    private boolean isWorking(UUID agentId) {
        return jdbc.sql("SELECT availability IN ('AVAILABLE','BUSY') AND active FROM agents WHERE id = :a").param("a", agentId)
                .query(Boolean.class).optional().orElse(false);
    }
}
