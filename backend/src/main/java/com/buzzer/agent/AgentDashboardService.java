package com.buzzer.agent;

import com.buzzer.common.SettingsService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent dashboard across all verticals: work queues (pending RFQs, vendor follow-ups, active orders, service bookings,
 * pending customer responses, escalations), an SLA countdown and performance metrics. Scope = requests where the agent is
 * the handling agent or the escalation / cover target.
 */
@Service
public class AgentDashboardService {
    private static final String MINE = "(r.agent_id = :a OR r.escalated_to_agent_id = :a)";
    private static final String OPEN = "r.status NOT IN ('SETTLED','CANCELLED','REJECTED','EXPIRED','REFUNDED','COMPLETED','VENDOR_REJECTED')";

    private final JdbcClient jdbc;
    private final SettingsService settings;

    public AgentDashboardService(JdbcClient jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public Map<String, Object> workspace(UUID agentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queues", jdbc.sql("SELECT "
                + "count(*) FILTER (WHERE r.request_type = 'PRODUCT_RFQ' AND r.lifecycle = 'REQUEST_V1' AND r.status IN ('REQUEST_CREATED','AGENT_REVIEW','PARTNER_QUOTATION','CUSTOMER_APPROVAL')) AS pending_rfqs, "
                + "count(*) FILTER (WHERE r.status = 'PARTNER_QUOTATION' AND r.updated_at < now() - make_interval(mins => :fu) "
                + "  AND NOT EXISTS (SELECT 1 FROM quotations q WHERE q.request_id = r.id AND q.status IN ('SUBMITTED','SHARED','REVISION_REQUESTED'))) AS vendor_follow_ups, "
                + "count(*) FILTER (WHERE r.lifecycle = 'ORDER' AND r.status NOT IN ('COMPLETED','CANCELLED','VENDOR_REJECTED')) AS active_orders, "
                + "count(*) FILTER (WHERE r.request_type = 'SERVICE_BOOKING' AND r.status IN ('PARTNER_ASSIGNED','PARTNER_CONFIRMED','RESCHEDULING_REQUESTED','PARTNER_ARRIVED','SERVICE_STARTED','IN_PROGRESS','COMPLETION_PENDING')) AS service_bookings, "
                + "count(*) FILTER (WHERE r.request_type = 'TASK_REQUEST' AND " + OPEN + ") AS tasks, "
                + "count(*) FILTER (WHERE r.status IN ('CUSTOMER_APPROVAL','PAYMENT_PENDING')) AS waiting_on_customer, "
                + "count(*) FILTER (WHERE r.escalated_to_agent_id = :a AND " + OPEN + ") AS escalated_to_me, "
                + "count(*) FILTER (WHERE r.status IN ('PARTNER_DECLINED','NO_SHOW','DISPUTED')) AS needs_attention, "
                + "count(*) FILTER (WHERE r.status IN ('COMPLETED','SETTLEMENT_PENDING','SETTLED') AND r.updated_at > now() - interval '30 days') AS completed_30d, "
                + "(SELECT count(*) FROM agent_drafts d WHERE d.agent_id = :a AND d.status = 'SENT' AND d.expires_at > now()) AS drafts_waiting "
                + "FROM service_requests r WHERE " + MINE)
                .param("a", agentId).param("fu", settings.getInt("agent.vendor_follow_up_minutes", 120)).query().singleRow());
        out.put("by_vertical", jdbc.sql("""
                SELECT v.code AS vertical, v.name,
                       count(r.id) FILTER (WHERE r.id IS NOT NULL AND r.status NOT IN ('SETTLED','CANCELLED','REJECTED','EXPIRED','REFUNDED','COMPLETED','VENDOR_REJECTED')) AS open,
                       count(r.id) FILTER (WHERE r.status IN ('COMPLETED','SETTLEMENT_PENDING','SETTLED') AND r.updated_at > now() - interval '30 days') AS completed_30d
                FROM platform_verticals v LEFT JOIN service_requests r ON r.vertical = v.code AND (r.agent_id = :a OR r.escalated_to_agent_id = :a)
                GROUP BY v.code, v.name, v.sort_order ORDER BY v.sort_order""").param("a", agentId).query().listOfRows());
        out.put("sla_countdown", jdbc.sql("""
                SELECT r.id, r.request_number, r.request_type, r.vertical, r.status, r.priority, r.sla_due_at, cu.full_name AS customer_name,
                       round(CAST(EXTRACT(EPOCH FROM (r.sla_due_at - now())) / 60 AS numeric), 0) AS minutes_left,
                       (r.escalated_to_agent_id = :a) AS escalated_to_me
                FROM service_requests r JOIN users cu ON cu.id = r.customer_user_id
                WHERE (r.agent_id = :a OR r.escalated_to_agent_id = :a) AND r.handling_mode = 'AGENT' AND r.first_agent_response_at IS NULL
                  AND r.sla_due_at IS NOT NULL AND r.status IN ('REQUEST_CREATED','AGENT_REVIEW')
                ORDER BY r.sla_due_at LIMIT 10""").param("a", agentId).query().listOfRows());
        out.put("metrics_30d", jdbc.sql("""
                SELECT count(*) AS requests,
                       round(CAST(avg(EXTRACT(EPOCH FROM (first_agent_response_at - created_at)) / 60) FILTER (WHERE first_agent_response_at IS NOT NULL) AS numeric), 1) AS avg_first_response_minutes,
                       round(100.0 * count(*) FILTER (WHERE first_agent_response_at IS NOT NULL AND first_agent_response_at <= sla_due_at)
                             / NULLIF(count(*) FILTER (WHERE first_agent_response_at IS NOT NULL OR sla_due_at < now()), 0), 0) AS sla_met_percent,
                       (SELECT count(*) FROM agent_drafts d WHERE d.agent_id = :a AND d.created_at > now() - interval '30 days') AS drafts_sent,
                       (SELECT count(*) FROM agent_drafts d WHERE d.agent_id = :a AND d.status = 'APPROVED' AND d.created_at > now() - interval '30 days') AS drafts_approved
                FROM service_requests WHERE agent_id = :a AND handling_mode = 'AGENT' AND created_at > now() - interval '30 days'""")
                .param("a", agentId).query().singleRow());
        out.put("recent_activity", jdbc.sql("""
                SELECT x.kind, x.summary, x.created_at, cu.full_name AS customer_name FROM agent_activities x LEFT JOIN users cu ON cu.id = x.customer_user_id
                WHERE x.agent_id = :a ORDER BY x.created_at DESC LIMIT 8""").param("a", agentId).query().listOfRows());
        return out;
    }

    public List<Map<String, Object>> skills(UUID agentId) {
        return jdbc.sql("SELECT vertical FROM agent_skills WHERE agent_id = :a ORDER BY vertical").param("a", agentId).query().listOfRows();
    }
}
