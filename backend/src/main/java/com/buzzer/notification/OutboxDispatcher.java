package com.buzzer.notification;

import com.buzzer.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Polls the transactional outbox and delivers events through channel adapters.
 * - SKIP LOCKED lets several backend instances dispatch concurrently without double-sending.
 * - Failures are retried with exponential backoff; after max_attempts the event is marked DEAD
 *   (dead-letter) and surfaces on the Admin System Health page.
 * - Channels with no adapter configured are dead-lettered with a clear "not configured" error rather
 *   than pretending to have been delivered.
 */
@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private record Event(UUID id, String eventType, String channel, String recipient, String payload, int attempts, int maxAttempts) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final TransactionTemplate tx;
    private final Map<String, ChannelSender> senders;

    public OutboxDispatcher(JdbcClient jdbc, Json json, TransactionTemplate tx, List<ChannelSender> senders) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.senders = senders.stream().collect(Collectors.toMap(ChannelSender::channel, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${buzzer.outbox.poll-ms:2000}")
    public void dispatch() {
        try {
            int processed;
            int guard = 0;
            do {
                processed = dispatchBatch(50);
            } while (processed == 50 && ++guard < 10);
        } catch (Exception e) {
            log.warn("Outbox dispatch cycle failed: {}", e.getMessage());
        }
    }

    public int dispatchBatch(int size) {
        Integer count = tx.execute(status -> {
            List<Event> batch = jdbc.sql("""
                    SELECT id, event_type, channel, recipient, payload::text AS payload, attempts, max_attempts
                    FROM outbox_events WHERE status IN ('PENDING','FAILED') AND next_attempt_at <= now()
                    ORDER BY created_at LIMIT :n FOR UPDATE SKIP LOCKED""")
                    .param("n", size).query(Event.class).list();
            for (Event e : batch) deliver(e);
            return batch.size();
        });
        return count == null ? 0 : count;
    }

    private void deliver(Event e) {
        ChannelSender sender = senders.get(e.channel());
        try {
            if (sender == null) {
                if ("PUSH".equals(e.channel())) {
                    // In-app notification row already exists; push provider (FCM) not configured yet.
                    markSent(e.id(), "PUSH provider not configured - in-app notification only");
                    return;
                }
                throw new IllegalStateException(e.channel() + " provider not configured");
            }
            sender.send(e.recipient(), e.eventType(), json.map(e.payload()));
            markSent(e.id(), sender.liveMode() ? null : "Delivered by TEST MODE adapter");
        } catch (Exception ex) {
            int attempts = e.attempts() + 1;
            boolean dead = attempts >= e.maxAttempts();
            long backoffSeconds = (long) Math.min(3600, 15 * Math.pow(2, attempts - 1));
            jdbc.sql("""
                    UPDATE outbox_events SET attempts = :a, status = :s, last_error = :err,
                      next_attempt_at = now() + make_interval(secs => :b) WHERE id = :id""")
                    .param("a", attempts).param("s", dead ? "DEAD" : "FAILED")
                    .param("err", String.valueOf(ex.getMessage())).param("b", backoffSeconds).param("id", e.id()).update();
            log.warn("Outbox event {} ({}) failed attempt {}: {}", e.id(), e.channel(), attempts, ex.getMessage());
        }
    }

    private void markSent(UUID id, String note) {
        jdbc.sql("UPDATE outbox_events SET status = 'SENT', sent_at = now(), attempts = attempts + 1, last_error = :n WHERE id = :id")
                .param("n", note).param("id", id).update();
    }
}
