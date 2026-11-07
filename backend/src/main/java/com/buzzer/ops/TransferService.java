package com.buzzer.ops;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.notification.NotificationService;
import com.buzzer.request.RequestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transferring work between people: a request (ticket) to another agent or manager, and an operational
 * exception to a new owner. Every transfer is recorded in the request timeline and the audit log, and both
 * the new and the previous handler are notified. Agents belong to wings (Home Services, Events, ...), so a
 * wing manager can later take or route everything for their wing.
 */
@Service
public class TransferService {
    private final JdbcClient jdbc;
    private final RequestRepository requests;
    private final NotificationService notifications;
    private final AuditService audit;

    public TransferService(JdbcClient jdbc, RequestRepository requests, NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.notifications = notifications;
        this.audit = audit;
    }

    /** Staff who can receive a transfer, grouped by wing in the UI. */
    public List<Map<String, Object>> staff() {
        return jdbc.sql("""
                SELECT u.id AS user_id, u.full_name, u.phone, a.id AS agent_id, a.display_name, a.wing, a.is_manager, a.availability,
                       (SELECT string_agg(r.role, ',' ORDER BY r.role) FROM user_roles r WHERE r.user_id = u.id
                          AND r.role IN ('AGENT','AGENT_MANAGER','SUPPORT','FINANCE','ADMIN','SUPER_ADMIN')) AS roles,
                       (SELECT count(*) FROM service_requests sr WHERE (sr.agent_id = a.id OR sr.escalated_to_agent_id = a.id)
                          AND sr.status NOT IN ('SETTLED','CANCELLED','REJECTED','EXPIRED','REFUNDED')) AS open_requests
                FROM users u LEFT JOIN agents a ON a.user_id = u.id AND a.active
                WHERE u.status = 'ACTIVE' AND EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id
                    AND r.role IN ('AGENT','AGENT_MANAGER','SUPPORT','FINANCE','ADMIN','SUPER_ADMIN'))
                ORDER BY a.wing NULLS LAST, a.is_manager DESC NULLS LAST, COALESCE(a.display_name, u.full_name)""").query().listOfRows();
    }

    @Transactional
    public void transferRequest(UUID requestId, UUID toAgentId, String reason, UUID byUserId, String byRole) {
        if (reason == null || reason.trim().length() < 3) throw BusinessException.invalid("Give a reason for the transfer");
        RequestRepository.Row r = requests.lock(requestId);
        record Agent(UUID id, UUID userId, String displayName, String availability) {}
        Agent to = jdbc.sql("SELECT id, user_id, display_name, availability FROM agents WHERE id = :a AND active").param("a", toAgentId)
                .query(Agent.class).optional().orElseThrow(() -> BusinessException.notFound("Agent"));
        if (to.id().equals(r.agentId()) && r.escalatedToAgentId() == null) throw BusinessException.invalid("The request is already with this agent");
        UUID previousUser = r.agentId() == null ? null
                : jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", r.agentId()).query(UUID.class).optional().orElse(null);
        jdbc.sql("""
                UPDATE service_requests SET agent_id = :a, escalated_to_agent_id = NULL, updated_at = now() WHERE id = :id""")
                .param("a", to.id()).param("id", requestId).update();
        jdbc.sql("""
                INSERT INTO request_status_history(request_id, from_status, to_status, actor_user_id, actor_role, reason)
                VALUES (:r, :s, :s, :by, :role, :reason)""")
                .param("r", requestId).param("s", r.status()).param("by", byUserId).param("role", byRole)
                .param("reason", "Transferred to " + to.displayName() + ": " + reason.trim()).update();
        // Open alerts about this request move to the new handler.
        jdbc.sql("""
                UPDATE ops_exceptions SET owner_user_id = :u, status = 'ACKNOWLEDGED'
                WHERE ref_type = 'REQUEST' AND ref_id = :r AND status IN ('OPEN','ACKNOWLEDGED')""")
                .param("u", to.userId()).param("r", requestId).update();
        audit.record(byUserId, byRole, "REQUEST_TRANSFERRED", "REQUEST", requestId,
                Map.of("toAgent", to.id().toString(), "fromAgent", String.valueOf(r.agentId()), "reason", reason.trim()));
        notifications.notifyUser(to.userId(), "Request transferred to you: " + r.requestNumber(), reason.trim(), "AGENT_ESCALATION", "REQUEST", requestId);
        if (previousUser != null && !previousUser.equals(to.userId())) {
            notifications.notifyUser(previousUser, "Request " + r.requestNumber() + " moved to " + to.displayName(), reason.trim(),
                    "AGENT_ALERT", "REQUEST", requestId);
        }
    }

    @Transactional
    public void assignException(UUID exceptionId, UUID ownerUserId, String note, UUID byUserId, String byRole) {
        Integer staff = jdbc.sql("""
                SELECT count(*) FROM user_roles WHERE user_id = :u AND role IN ('AGENT','AGENT_MANAGER','SUPPORT','FINANCE','ADMIN','SUPER_ADMIN')""")
                .param("u", ownerUserId).query(Integer.class).single();
        if (staff == 0) throw BusinessException.invalid("Owner must be a staff member");
        record Ex(String title, String status) {}
        Ex ex = jdbc.sql("SELECT title, status FROM ops_exceptions WHERE id = :id FOR UPDATE").param("id", exceptionId).query(Ex.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Exception"));
        if (List.of("RESOLVED", "DISMISSED").contains(ex.status())) throw BusinessException.invalid("This exception is already closed");
        jdbc.sql("""
                UPDATE ops_exceptions SET owner_user_id = :u, status = 'ACKNOWLEDGED',
                  resolution_note = COALESCE(:n, resolution_note) WHERE id = :id""")
                .param("u", ownerUserId).param("n", note).param("id", exceptionId).update();
        audit.record(byUserId, byRole, "EXCEPTION_ASSIGNED", "OPS_EXCEPTION", exceptionId, Map.of("owner", ownerUserId.toString(),
                "note", note == null ? "" : note));
        notifications.notifyUser(ownerUserId, "Assigned to you: " + ex.title(), note == null ? "Please take ownership of this issue." : note,
                "AGENT_ESCALATION", "OPS_EXCEPTION", exceptionId);
    }
}
