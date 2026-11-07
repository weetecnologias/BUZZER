package com.buzzer.notification;

import com.buzzer.common.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-app notifications are written directly (same transaction as the business change).
 * External deliveries (SMS / push / email / WhatsApp) are written to the transactional outbox and
 * dispatched asynchronously, so a provider outage never blocks or loses a business operation.
 */
@Service
public class NotificationService {
    private final JdbcClient jdbc;
    private final Json json;

    public NotificationService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void notifyUser(UUID userId, String title, String body, String category, String refType, UUID refId) {
        if (userId == null) return;
        jdbc.sql("""
                INSERT INTO notifications(user_id, title, body, category, ref_type, ref_id)
                VALUES (:u, :t, :b, :c, :rt, :ri)""")
                .param("u", userId).param("t", title).param("b", body).param("c", category)
                .param("rt", refType).param("ri", refId).update();
        jdbc.sql("""
                INSERT INTO outbox_events(event_type, channel, recipient, payload)
                VALUES (:e, 'PUSH', :r, CAST(:p AS jsonb))""")
                .param("e", category).param("r", userId.toString())
                .param("p", json.write(Map.of("title", title, "body", body))).update();
    }

    public void enqueueSms(String phone, String eventType, Map<String, Object> payload) {
        jdbc.sql("""
                INSERT INTO outbox_events(event_type, channel, recipient, payload)
                VALUES (:e, 'SMS', :r, CAST(:p AS jsonb))""")
                .param("e", eventType).param("r", phone).param("p", json.write(payload)).update();
    }

    public List<Map<String, Object>> list(UUID userId, int limit) {
        return jdbc.sql("""
                SELECT id, title, body, category, ref_type, ref_id, read_at, created_at FROM notifications
                WHERE user_id = :u ORDER BY created_at DESC LIMIT :l""")
                .param("u", userId).param("l", Math.min(Math.max(limit, 1), 200)).query().listOfRows();
    }

    public void markRead(UUID userId, UUID notificationId) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE id = :id AND user_id = :u AND read_at IS NULL")
                .param("id", notificationId).param("u", userId).update();
    }

    public int unreadCount(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :u AND read_at IS NULL")
                .param("u", userId).query(Integer.class).single();
    }
}
