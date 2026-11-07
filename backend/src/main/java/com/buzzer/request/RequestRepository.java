package com.buzzer.request;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class RequestRepository {
    public record Row(UUID id, String requestNumber, UUID customerUserId, String status, UUID agentId, UUID escalatedToAgentId,
                      UUID categoryId, UUID subcategoryId, UUID serviceItemId, UUID cityId, UUID localityId, String priority) {
        public RequestStatus statusEnum() {
            return RequestStatus.valueOf(status);
        }
    }

    private static final String COLS = """
            id, request_number, customer_user_id, status, agent_id, escalated_to_agent_id, category_id, subcategory_id,
            service_item_id, city_id, locality_id, priority""";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public RequestRepository(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Pessimistic row lock: serialises all state changes on one request. Must be called inside a transaction. */
    public Row lock(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM service_requests WHERE id = :id FOR UPDATE").param("id", id)
                .query(Row.class).optional().orElseThrow(() -> BusinessException.notFound("Request"));
    }

    public Row get(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM service_requests WHERE id = :id").param("id", id)
                .query(Row.class).optional().orElseThrow(() -> BusinessException.notFound("Request"));
    }

    /** Validated state transition with history + audit. Caller must hold the row lock. */
    public void transition(Row row, RequestStatus to, UUID actor, String role, String reason) {
        RequestStatus from = row.statusEnum();
        if (from == to) return;
        if (!from.canMoveTo(to)) throw BusinessException.transition(from.name(), to.name());
        jdbc.sql("""
                UPDATE service_requests SET status = :to, version = version + 1, updated_at = now(),
                  resolved_at = CASE WHEN :to IN ('SETTLED','CANCELLED','REJECTED','EXPIRED','REFUNDED') THEN now() ELSE resolved_at END
                WHERE id = :id""").param("to", to.name()).param("id", row.id()).update();
        jdbc.sql("""
                INSERT INTO request_status_history(request_id, from_status, to_status, actor_user_id, actor_role, reason)
                VALUES (:r, :f, :t, :a, :role, :reason)""")
                .param("r", row.id()).param("f", from.name()).param("t", to.name()).param("a", actor).param("role", role)
                .param("reason", reason).update();
        audit.record(actor, role, "REQUEST_STATUS_CHANGED", "REQUEST", row.id(), Map.of("from", from.name(), "to", to.name(),
                "reason", reason == null ? "" : reason));
    }

    /** Timeline entry without a status change (e.g. "quote received", "transferred", "handed to agent"). */
    public void note(UUID requestId, String status, UUID actor, String role, String reason) {
        jdbc.sql("""
                INSERT INTO request_status_history(request_id, from_status, to_status, actor_user_id, actor_role, reason)
                VALUES (:r, :s, :s, :a, :role, :reason)""")
                .param("r", requestId).param("s", status).param("a", actor).param("role", role).param("reason", reason).update();
        jdbc.sql("UPDATE service_requests SET updated_at = now() WHERE id = :r").param("r", requestId).update();
    }

    public List<Map<String, Object>> history(UUID requestId) {
        return jdbc.sql("""
                SELECT h.from_status, h.to_status, h.actor_role, h.reason, h.created_at, u.full_name AS actor_name
                FROM request_status_history h LEFT JOIN users u ON u.id = h.actor_user_id
                WHERE h.request_id = :r ORDER BY h.created_at, h.id""").param("r", requestId).query().listOfRows();
    }

    public List<Map<String, Object>> messages(UUID requestId) {
        return jdbc.sql("""
                SELECT m.id, m.sender_role, m.body, m.created_at, u.full_name AS sender_name
                FROM request_messages m JOIN users u ON u.id = m.sender_user_id WHERE m.request_id = :r ORDER BY m.created_at""")
                .param("r", requestId).query().listOfRows();
    }

    public void addMessage(UUID requestId, UUID sender, String role, String body) {
        if (body == null || body.isBlank() || body.length() > 2000) throw BusinessException.invalid("Message must be 1-2000 characters");
        jdbc.sql("INSERT INTO request_messages(request_id, sender_user_id, sender_role, body) VALUES (:r, :s, :role, :b)")
                .param("r", requestId).param("s", sender).param("role", role).param("b", body.trim()).update();
    }

    /** Summary columns shared by list views. */
    public static final String LIST_SELECT = """
            SELECT r.id, r.request_number, r.status, r.priority, r.created_at, r.updated_at, r.preferred_start, r.description,
                   r.created_via, r.booking_mode, r.estimated_total, r.sla_due_at, r.first_agent_response_at, r.escalated_at, r.customer_confirmed_at,
                   r.handling_mode, r.agent_source, r.agent_seen_at, r.handed_to_agent_at, r.handover_reason,
                   COALESCE(jsonb_array_length(CASE WHEN jsonb_typeof(r.line_items) = 'array' THEN r.line_items END), 0) AS item_count,
                   (SELECT max(h.created_at) FROM request_status_history h WHERE h.request_id = r.id) AS last_activity_at,
                   (SELECT count(*) FROM quotations q WHERE q.request_id = r.id AND q.status = 'SHARED') AS open_quote_count,
                   c.name AS category_name, c.icon AS category_icon, s.name AS subcategory_name, i.name AS service_name,
                   cu.full_name AS customer_name, cu.phone AS customer_phone, a.display_name AS agent_name,
                   l.name AS locality_name,
                   (SELECT count(*) FROM quotations q WHERE q.request_id = r.id) AS quotation_count
            FROM service_requests r
            JOIN categories c ON c.id = r.category_id
            LEFT JOIN subcategories s ON s.id = r.subcategory_id
            LEFT JOIN service_items i ON i.id = r.service_item_id
            JOIN users cu ON cu.id = r.customer_user_id
            LEFT JOIN agents a ON a.id = r.agent_id
            LEFT JOIN localities l ON l.id = r.locality_id
            """;
}
