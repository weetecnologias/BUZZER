package com.buzzer.ops;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * First-response SLA. Each business vertical has its own target (pilot: Services and Tasks 60 minutes, Shop & Buy and
 * Personal Assistance 120 minutes). HIGH / URGENT requests use the shorter of the vertical target and the priority rule.
 */
@Component
public class SlaPolicy {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public SlaPolicy(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public int firstResponseMinutes(String vertical, String priority) {
        Integer byVertical = vertical == null ? null : jdbc.sql("SELECT first_response_minutes FROM vertical_sla WHERE vertical = :v")
                .param("v", vertical).query(Integer.class).optional().orElse(null);
        Integer byPriority = jdbc.sql("SELECT first_response_minutes FROM sla_rules WHERE priority = :p AND active")
                .param("p", priority == null ? "NORMAL" : priority).query(Integer.class).optional().orElse(null);
        if (byVertical == null) return byPriority == null ? 120 : byPriority;
        if (byPriority != null && ("HIGH".equals(priority) || "URGENT".equals(priority))) return Math.min(byVertical, byPriority);
        return byVertical;
    }

    public List<Map<String, Object>> verticals() {
        return jdbc.sql("""
                SELECT v.code AS vertical, v.name, v.enabled, s.first_response_minutes, s.updated_at
                FROM platform_verticals v LEFT JOIN vertical_sla s ON s.vertical = v.code ORDER BY v.sort_order""").query().listOfRows();
    }

    @Transactional
    public List<Map<String, Object>> update(String vertical, Integer minutes, UUID admin) {
        if (minutes == null || minutes < 15 || minutes > 480) throw BusinessException.invalid("First response time must be 15-480 minutes (pilot target 60-120)");
        Integer exists = jdbc.sql("SELECT count(*) FROM platform_verticals WHERE code = :v").param("v", vertical).query(Integer.class).single();
        if (exists == 0) throw BusinessException.notFound("Vertical");
        jdbc.sql("""
                INSERT INTO vertical_sla(vertical, first_response_minutes, updated_by) VALUES (:v, :m, :a)
                ON CONFLICT (vertical) DO UPDATE SET first_response_minutes = :m, updated_by = :a, updated_at = now()""")
                .param("v", vertical).param("m", minutes).param("a", admin).update();
        audit.record(admin, "ADMIN", "VERTICAL_SLA_UPDATED", "VERTICAL", null, Map.of("vertical", vertical, "minutes", minutes));
        return verticals();
    }
}
