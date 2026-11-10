package com.buzzer.agent;

import com.buzzer.common.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Agent activity log: what each agent did for whom (calls, notes, vendor follow-ups, drafts, escalations, cover). */
@Service
public class AgentActivityService {
    /** Kinds an agent can log by hand; the rest are written by the system. */
    public static final Set<String> MANUAL = Set.of("CALL", "NOTE", "VENDOR_FOLLOW_UP");

    private final JdbcClient jdbc;

    public AgentActivityService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void log(UUID agentId, String kind, String summary, UUID customerUserId, UUID requestId, UUID partnerId, UUID draftId) {
        if (agentId == null) return;
        String text = summary == null ? kind : summary.length() > 500 ? summary.substring(0, 500) : summary;
        jdbc.sql("""
                INSERT INTO agent_activities(agent_id, customer_user_id, request_id, partner_id, draft_id, kind, summary)
                VALUES (:a, :c, :r, :p, :d, :k, :s)""")
                .param("a", agentId).param("c", customerUserId).param("r", requestId).param("p", partnerId).param("d", draftId)
                .param("k", kind).param("s", text).update();
    }

    /** Activity of one agent (or, for a manager, their team), optionally for one customer or request. */
    public List<Map<String, Object>> list(UUID agentId, boolean team, UUID customerUserId, UUID requestId, int limit) {
        return jdbc.sql("""
                SELECT x.id, x.kind, x.summary, x.created_at, x.request_id, r.request_number, x.customer_user_id, cu.full_name AS customer_name,
                       x.partner_id, p.display_name AS partner_name, x.draft_id, d.draft_number, a.id AS agent_id, a.display_name AS agent_name
                FROM agent_activities x JOIN agents a ON a.id = x.agent_id
                LEFT JOIN service_requests r ON r.id = x.request_id LEFT JOIN users cu ON cu.id = x.customer_user_id
                LEFT JOIN partners p ON p.id = x.partner_id LEFT JOIN agent_drafts d ON d.id = x.draft_id
                WHERE (x.agent_id = :a OR (:team AND a.manager_id = :a))
                  AND (CAST(:c AS uuid) IS NULL OR x.customer_user_id = CAST(:c AS uuid))
                  AND (CAST(:r AS uuid) IS NULL OR x.request_id = CAST(:r AS uuid))
                ORDER BY x.created_at DESC LIMIT :lim""")
                .param("a", agentId).param("team", team).param("c", customerUserId).param("r", requestId)
                .param("lim", Math.min(Math.max(limit, 1), 500)).query().listOfRows();
    }

    /** Everything any agent did for a request / customer (admin and request timelines). */
    public List<Map<String, Object>> forScope(UUID customerUserId, UUID requestId, int limit) {
        if (customerUserId == null && requestId == null) throw BusinessException.invalid("Choose a customer or a request");
        return jdbc.sql("""
                SELECT x.id, x.kind, x.summary, x.created_at, x.request_id, a.display_name AS agent_name, p.display_name AS partner_name
                FROM agent_activities x JOIN agents a ON a.id = x.agent_id LEFT JOIN partners p ON p.id = x.partner_id
                WHERE (CAST(:c AS uuid) IS NULL OR x.customer_user_id = CAST(:c AS uuid)) AND (CAST(:r AS uuid) IS NULL OR x.request_id = CAST(:r AS uuid))
                ORDER BY x.created_at DESC LIMIT :lim""")
                .param("c", customerUserId).param("r", requestId).param("lim", Math.min(Math.max(limit, 1), 500)).query().listOfRows();
    }
}
