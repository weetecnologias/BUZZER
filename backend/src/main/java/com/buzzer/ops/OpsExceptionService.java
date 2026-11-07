package com.buzzer.ops;

import com.buzzer.common.BusinessException;
import com.buzzer.common.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operational exceptions shown on the Admin dashboard (SLA breaches, repeated partner cancellations,
 * failed payments, missing agents, reconciliation mismatches...). Each has priority, owner, ageing,
 * status, next action and audit history. A dedupe key prevents duplicate open alerts for one problem.
 */
@Service
public class OpsExceptionService {
    private final JdbcClient jdbc;
    private final Json json;

    public OpsExceptionService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void raise(String type, String priority, String title, String refType, UUID refId, String nextAction,
                      String dedupeKey, Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO ops_exceptions(exception_type, priority, title, ref_type, ref_id, next_action, dedupe_key, details)
                VALUES (:t, :p, :title, :rt, :ri, :na, :dk, CAST(:d AS jsonb))
                ON CONFLICT (dedupe_key) WHERE status IN ('OPEN','ACKNOWLEDGED') AND dedupe_key IS NOT NULL DO NOTHING""")
                .param("t", type).param("p", priority).param("title", title).param("rt", refType).param("ri", refId)
                .param("na", nextAction).param("dk", dedupeKey).param("d", json.write(details == null ? Map.of() : details)).update();
    }

    public List<Map<String, Object>> list(String status, String type, int limit) {
        return jdbc.sql("""
                SELECT e.id, e.exception_type, e.priority, e.status, e.title, e.details::text AS details, e.ref_type, e.ref_id,
                       e.owner_user_id, u.full_name AS owner_name, e.next_action, e.created_at, e.resolved_at, e.resolution_note,
                       EXTRACT(EPOCH FROM (COALESCE(e.resolved_at, now()) - e.created_at))::bigint / 60 AS ageing_minutes
                FROM ops_exceptions e LEFT JOIN users u ON u.id = e.owner_user_id
                WHERE (CAST(:s AS text) IS NULL OR e.status = CAST(:s AS text))
                  AND (CAST(:t AS text) IS NULL OR e.exception_type = CAST(:t AS text))
                ORDER BY CASE e.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END, e.created_at DESC
                LIMIT :l""").param("s", status).param("t", type).param("l", Math.min(limit, 500)).query().listOfRows();
    }

    public void updateStatus(UUID id, String status, UUID by, String note) {
        if (!List.of("ACKNOWLEDGED", "RESOLVED", "DISMISSED").contains(status)) throw BusinessException.invalid("Invalid status");
        int n = jdbc.sql("""
                UPDATE ops_exceptions SET status = :s, owner_user_id = COALESCE(owner_user_id, :by),
                  resolved_at = CASE WHEN :s IN ('RESOLVED','DISMISSED') THEN now() ELSE resolved_at END,
                  resolution_note = COALESCE(:n, resolution_note) WHERE id = :id""")
                .param("s", status).param("by", by).param("n", note).param("id", id).update();
        if (n == 0) throw BusinessException.notFound("Exception");
    }

    public Map<String, Object> summary() {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status IN ('OPEN','ACKNOWLEDGED')) AS open_total,
                       count(*) FILTER (WHERE status IN ('OPEN','ACKNOWLEDGED') AND exception_type = 'SLA_BREACH') AS sla_breaches,
                       count(*) FILTER (WHERE status IN ('OPEN','ACKNOWLEDGED') AND exception_type = 'PARTNER_REPEATED_CANCELLATIONS') AS repeated_cancellations,
                       count(*) FILTER (WHERE status IN ('OPEN','ACKNOWLEDGED') AND exception_type LIKE 'PAYMENT%') AS payment_issues
                FROM ops_exceptions""").query().singleRow();
    }
}
