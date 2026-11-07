package com.buzzer.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** Append-only audit trail for significant business actions. */
@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final Json json;

    public AuditService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID actorUserId, String actorRole, String action, String entityType, UUID entityId, Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO audit_events(actor_user_id, actor_role, action, entity_type, entity_id, details)
                VALUES (:actor, :role, :action, :etype, :eid, CAST(:details AS jsonb))""")
                .param("actor", actorUserId)
                .param("role", actorRole)
                .param("action", action)
                .param("etype", entityType)
                .param("eid", entityId)
                .param("details", json.write(details == null ? Map.of() : details))
                .update();
    }
}
