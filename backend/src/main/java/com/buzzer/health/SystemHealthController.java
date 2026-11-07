package com.buzzer.health;

import com.buzzer.common.ApiResponse;
import com.buzzer.notification.ChannelSender;
import com.buzzer.payment.PaymentProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin System Health. Each component reports HEALTHY / DEGRADED / UNAVAILABLE / UNKNOWN. Components that
 * are not provisioned in this environment (replica, Redis, backups) honestly report UNKNOWN rather than green.
 * No secrets or customer data are included.
 */
@RestController
public class SystemHealthController {
    private final JdbcClient jdbc;
    private final PaymentProvider paymentProvider;
    private final List<ChannelSender> senders;
    private final Map<String, String> frontendUrls;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SystemHealthController(JdbcClient jdbc, PaymentProvider paymentProvider, List<ChannelSender> senders,
                                  @Value("${buzzer.health.customer-app-url:}") String customerUrl,
                                  @Value("${buzzer.health.partner-app-url:}") String partnerUrl,
                                  @Value("${buzzer.health.agent-app-url:}") String agentUrl,
                                  @Value("${buzzer.health.admin-panel-url:}") String adminUrl) {
        this.jdbc = jdbc;
        this.paymentProvider = paymentProvider;
        this.senders = senders;
        this.frontendUrls = new LinkedHashMap<>();
        frontendUrls.put("Customer App (web build)", customerUrl);
        frontendUrls.put("Partner App (web build)", partnerUrl);
        frontendUrls.put("Agent App (web build)", agentUrl);
        frontendUrls.put("Admin Panel", adminUrl);
    }

    private static Map<String, Object> component(String name, String group, String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("group", group);
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }

    @GetMapping("/api/v1/admin/system-health")
    public ApiResponse<Map<String, Object>> health() {
        List<Map<String, Object>> c = new ArrayList<>();
        frontendUrls.forEach((name, url) -> c.add(probe(name, url)));
        c.add(component("Backend API", "Core", "HEALTHY", "Uptime " + ManagementFactory.getRuntimeMXBean().getUptime() / 60000 + " min"));

        long t0 = System.nanoTime();
        String dbStatus;
        String dbDetail;
        try {
            Boolean recovery = jdbc.sql("SELECT pg_is_in_recovery()").query(Boolean.class).single();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            dbStatus = Boolean.TRUE.equals(recovery) ? "DEGRADED" : (ms > 500 ? "DEGRADED" : "HEALTHY");
            dbDetail = (Boolean.TRUE.equals(recovery) ? "Connected to a read-only standby! " : "Primary reachable, ") + ms + " ms";
        } catch (Exception e) {
            dbStatus = "UNAVAILABLE";
            dbDetail = "Database unreachable";
        }
        c.add(component("Database primary", "Data", dbStatus, dbDetail));
        Integer replicas = safeInt("SELECT count(*) FROM pg_stat_replication");
        c.add(component("Database replica", "Data", replicas == null ? "UNKNOWN" : replicas > 0 ? "HEALTHY" : "UNKNOWN",
                replicas == null ? "Not visible" : replicas > 0 ? replicas + " streaming replica(s)" : "No standby visible from this instance (managed HA reports separately)"));
        c.add(component("Redis cache", "Data", "UNKNOWN", "Not provisioned in this environment"));

        Map<String, Object> outbox = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status IN ('PENDING','FAILED')) AS backlog, count(*) FILTER (WHERE status = 'DEAD') AS dead,
                       COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at) FILTER (WHERE status IN ('PENDING','FAILED'))))::bigint, 0) AS oldest_seconds
                FROM outbox_events""").query().singleRow();
        long backlog = ((Number) outbox.get("backlog")).longValue();
        long dead = ((Number) outbox.get("dead")).longValue();
        long oldest = ((Number) outbox.get("oldest_seconds")).longValue();
        c.add(component("Queue (transactional outbox)", "Processing", oldest > 600 ? "DEGRADED" : "HEALTHY",
                "Backlog " + backlog + ", oldest " + oldest + "s, dead-letter " + dead));

        Map<String, Object> pay = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'FAILED') AS failed, count(*) AS total FROM payments WHERE created_at > now() - interval '1 hour'""")
                .query().singleRow();
        c.add(component("Payment gateway (" + paymentProvider.name() + ")", "Integrations", paymentProvider.testMode() ? "DEGRADED" : "HEALTHY",
                (paymentProvider.testMode() ? "TEST MODE - no real money moves. " : "") + pay.get("failed") + "/" + pay.get("total") + " failed in last hour"));
        for (String ch : List.of("SMS", "EMAIL", "WHATSAPP", "PUSH")) {
            ChannelSender s = senders.stream().filter(x -> x.channel().equals(ch)).findFirst().orElse(null);
            Integer deadCh = safeInt("SELECT count(*) FROM outbox_events WHERE channel = '" + ch + "' AND status = 'DEAD' AND created_at > now() - interval '1 day'");
            String status = s == null ? "UNKNOWN" : !s.liveMode() ? "DEGRADED" : (deadCh != null && deadCh > 0 ? "DEGRADED" : "HEALTHY");
            String detail = s == null ? "Provider not configured" : (!s.liveMode() ? "TEST MODE adapter (logs only)" : "Live") + "; dead-letter 24h: " + deadCh;
            c.add(component(ch, "Integrations", status, detail));
        }
        c.add(component("Recharge / travel integrations", "Integrations", "UNKNOWN", "No provider contracted yet - not active"));
        c.add(component("Backups", "Resilience", "UNKNOWN", "Managed by cloud provider; last backup not reported to this instance"));
        c.add(component("Last restore test", "Resilience", "UNKNOWN", "Record restore drills in docs/runbooks/backup-and-restore.md"));

        Map<String, Object> errors = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status IN ('OPEN','ACKNOWLEDGED')) AS open_exceptions FROM ops_exceptions""").query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("components", c);
        out.put("open_exceptions", errors.get("open_exceptions"));
        out.put("incidents", jdbc.sql("""
                SELECT exception_type, priority, status, title, created_at, resolved_at FROM ops_exceptions
                WHERE exception_type IN ('PAYMENT_GATEWAY_ERROR','PAYMENT_RECONCILIATION_MISMATCH','SLA_BREACH','NO_AGENT_AVAILABLE')
                ORDER BY created_at DESC LIMIT 20""").query().listOfRows());
        out.put("checked_at", java.time.OffsetDateTime.now().toString());
        return ApiResponse.of(out);
    }

    private Map<String, Object> probe(String name, String url) {
        if (url == null || url.isBlank()) return component(name, "Applications", "UNKNOWN", "Health URL not configured");
        try {
            long t0 = System.nanoTime();
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return component(name, "Applications", r.statusCode() < 400 ? (ms > 1500 ? "DEGRADED" : "HEALTHY") : "UNAVAILABLE",
                    "HTTP " + r.statusCode() + " in " + ms + " ms");
        } catch (Exception e) {
            return component(name, "Applications", "UNAVAILABLE", "Not reachable");
        }
    }

    private Integer safeInt(String sql) {
        try {
            return jdbc.sql(sql).query(Integer.class).single();
        } catch (Exception e) {
            return null;
        }
    }
}
