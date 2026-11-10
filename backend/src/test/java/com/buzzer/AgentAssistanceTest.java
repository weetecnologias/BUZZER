package com.buzzer;

import com.buzzer.ops.EscalationService;
import com.buzzer.support.ApiClient;
import com.buzzer.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Stage 7 acceptance: F (dedicated allocation), G (backup coverage), H (SLA per vertical + escalation), M (agents can't approve / pay), drafts. */
@AutoConfigureMockMvc
class AgentAssistanceTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired EscalationService escalations;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
        jdbc.sql("UPDATE platform_verticals SET enabled = TRUE").update();
        jdbc.sql("UPDATE partners SET availability = 'AVAILABLE'").update();
        jdbc.sql("UPDATE agents SET availability = 'AVAILABLE', accepts_dedicated = TRUE, max_customers = 300").update();
        jdbc.sql("INSERT INTO agent_skills(agent_id, vertical) SELECT a.id, v.code FROM agents a CROSS JOIN platform_verticals v ON CONFLICT DO NOTHING").update();
    }

    private String agentId(String phone) {
        return jdbc.sql("SELECT a.id::text FROM agents a JOIN users u ON u.id = a.user_id WHERE u.phone = ?").param("+91" + phone).query(String.class).single();
    }

    private String phoneOfAgent(String agentId) {
        return jdbc.sql("SELECT substring(u.phone from 4) FROM agents a JOIN users u ON u.id = a.user_id WHERE a.id = ?::uuid").param(agentId).query(String.class).single();
    }

    private String link(String customerToken, String role) {
        String user = api.get("/api/v1/auth/me", customerToken).data().path("id").asText();
        return jdbc.sql("SELECT agent_id::text FROM customer_agent_links WHERE customer_user_id = ?::uuid AND link_role = ? AND active")
                .param(user).param(role).query(String.class).optional().orElse(null);
    }

    private String userId(String token) {
        return api.get("/api/v1/auth/me", token).data().path("id").asText();
    }

    private String subscribe(List<String> languages, List<String> interestCategories) {
        String customer = api.loginOnly(ApiClient.randomPhone(), "CUSTOMER");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("languages", languages);
        body.put("interest_category_ids", interestCategories);
        var res = api.post("/api/v1/customer/agent/subscribe", customer, body);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return customer;
    }

    private String categoryId(String code) {
        return jdbc.sql("SELECT id::text FROM categories WHERE code = ?").param(code).query(String.class).single();
    }

    private String address(String token) {
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        String loc = jdbc.sql("SELECT id::text FROM localities WHERE name = 'Kukatpally'").query(String.class).single();
        return api.post("/api/v1/customer/addresses", token, Map.of("label", "HOME", "line1", "Flat 2, Lake View, Kukatpally", "city_id", city,
                "locality_id", loc, "pincode", "500072")).data().path("id").asText();
    }

    private String tapRepair() {
        return jdbc.sql("SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id WHERE s.code = 'PLUMBING' AND i.code = 'TAP_REPAIR'")
                .query(String.class).single();
    }

    /** A request the agent must respond to (customer sends it to their agent). */
    private JsonNode serviceRequest(String customer, String addr, String priority) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_item_id", tapRepair());
        body.put("address_id", addr);
        body.put("priority", priority);
        body.put("answers", Map.of("problem_area", "Kitchen", "leak_severity", "Slow drip", "details", "Kitchen tap drips all night long"));
        var res = api.post("/api/v1/customer/requests", customer, body);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.data();
    }

    private long slaMinutes(String requestId) {
        var row = jdbc.sql("SELECT created_at, sla_due_at FROM service_requests WHERE id = ?::uuid").param(requestId).query().singleRow();
        return Duration.between(((java.sql.Timestamp) row.get("created_at")).toInstant(), ((java.sql.Timestamp) row.get("sla_due_at")).toInstant()).toMinutes();
    }

    // ----- F: dedicated agent allocation by eligibility ----------------------------------------------------------

    @Test
    void dedicatedAgentsAreAllocatedByEligibilityLanguageAndVerticalSkills() {
        String rahul = agentId("9000000011"), priya = agentId("9000000012");
        String admin = api.login("9000000001", "ADMIN");
        try {
            // Rahul doesn't take dedicated customers -> never allocated as primary or backup, even for a Hindi speaker.
            assertThat(api.put("/api/v1/admin/agents/" + rahul + "/eligibility", admin, Map.of("accepts_dedicated", false)).status()).isEqualTo(200);
            String hindi = subscribe(List.of("hi"), List.of());
            assertThat(List.of(String.valueOf(link(hindi, "PRIMARY")), String.valueOf(link(hindi, "BACKUP")))).doesNotContain(rahul);
            assertThat(api.put("/api/v1/admin/agents/" + rahul + "/eligibility", admin, Map.of("accepts_dedicated", true)).status()).isEqualTo(200);
            String hindi2 = subscribe(List.of("hi"), List.of());
            assertThat(link(hindi2, "PRIMARY")).isEqualTo(rahul);
            assertThat(link(hindi2, "BACKUP")).isNotEqualTo(rahul);

            // Both speak Telugu: the customer interested in Shop & Buy gets the agent trained for it.
            assertThat(api.put("/api/v1/admin/agents/" + priya + "/eligibility", admin, Map.of("verticals", List.of("SERVICES", "ASSISTANCE"))).status()).isEqualTo(200);
            assertThat(api.put("/api/v1/admin/agents/" + rahul + "/eligibility", admin, Map.of("verticals", List.of("MARKETPLACE", "SERVICES"))).status()).isEqualTo(200);
            String shopper = subscribe(List.of("te"), List.of(categoryId("HARDWARE_SUPPLIES")));
            assertThat(link(shopper, "PRIMARY")).isEqualTo(rahul);
            String homeOwner = subscribe(List.of("te"), List.of(categoryId("HOME_REPAIRS")));
            assertThat(link(homeOwner, "PRIMARY")).isIn(rahul, priya);
            // Validation and permissions.
            assertThat(api.put("/api/v1/admin/agents/" + priya + "/eligibility", admin, Map.of("verticals", List.of())).status()).isEqualTo(400);
            assertThat(api.put("/api/v1/admin/agents/" + priya + "/eligibility", api.login("9000000012", "AGENT"), Map.of("accepts_dedicated", false)).status()).isEqualTo(403);
            assertThat(api.get("/api/v1/admin/agents/" + rahul + "/eligibility", admin).data().path("verticals").toString()).contains("MARKETPLACE");
        } finally {
            jdbc.sql("UPDATE agents SET accepts_dedicated = TRUE").update();
            jdbc.sql("INSERT INTO agent_skills(agent_id, vertical) SELECT a.id, v.code FROM agents a CROSS JOIN platform_verticals v ON CONFLICT DO NOTHING").update();
        }
    }

    // ----- G: backup coverage ---------------------------------------------------------------------------------

    @Test
    void whenThePrimaryAgentIsAwayTheBackupCoversNewRequests() {
        String customer = subscribe(List.of("en", "te"), List.of());
        String primary = link(customer, "PRIMARY"), backup = link(customer, "BACKUP");
        assertThat(primary).isNotNull();
        assertThat(backup).isNotNull().isNotEqualTo(primary);
        String primaryToken = api.login(phoneOfAgent(primary), "AGENT");
        try {
            assertThat(api.put("/api/v1/agent/availability", primaryToken, Map.of("availability", "ON_LEAVE")).status()).isEqualTo(200);
            JsonNode req = serviceRequest(customer, address(customer), "NORMAL");
            String requestId = req.path("id").asText();
            var row = jdbc.sql("SELECT agent_id::text AS agent, escalated_to_agent_id::text AS cover FROM service_requests WHERE id = ?::uuid").param(requestId).query().singleRow();
            assertThat(row.get("agent")).isEqualTo(primary);
            assertThat(row.get("cover")).isEqualTo(backup);
            assertThat(jdbc.sql("SELECT reason FROM request_status_history WHERE request_id = ?::uuid ORDER BY created_at DESC, id LIMIT 1").param(requestId)
                    .query(String.class).single()).contains("is away").contains("covering");
            // The backup sees it, can work on it and has it in their activity log and workspace.
            String backupToken = api.login(phoneOfAgent(backup), "AGENT");
            assertThat(api.get("/api/v1/agent/requests/" + requestId, backupToken).status()).isEqualTo(200);
            assertThat(api.get("/api/v1/agent/activity", backupToken).data().toString()).contains("COVERING_FOR_PRIMARY");
            assertThat(api.get("/api/v1/agent/workspace", backupToken).data().path("queues").path("escalated_to_me").asInt()).isGreaterThanOrEqualTo(1);
        } finally {
            api.put("/api/v1/agent/availability", primaryToken, Map.of("availability", "AVAILABLE"));
        }
        // Primary back at work: new requests go to them again, without cover.
        String again = serviceRequest(customer, address(customer), "NORMAL").path("id").asText();
        assertThat(jdbc.sql("SELECT escalated_to_agent_id IS NULL FROM service_requests WHERE id = ?::uuid").param(again).query(Boolean.class).single()).isTrue();
    }

    // ----- H: SLA per vertical and escalation -------------------------------------------------------------------

    @Test
    void firstResponseSlaIsSetPerVerticalAndBreachesEscalateToTheBackup() {
        String admin = api.login("9000000001", "ADMIN");
        JsonNode verticals = api.get("/api/v1/admin/sla/verticals", admin).data();
        assertThat(verticals.toString()).contains("\"SERVICES\"").contains("\"first_response_minutes\":60");
        String customer = subscribe(List.of("en"), List.of());
        String addr = address(customer);
        try {
            String r1 = serviceRequest(customer, addr, "NORMAL").path("id").asText();
            assertThat(slaMinutes(r1)).isBetween(59L, 60L);
            assertThat(api.put("/api/v1/admin/sla/verticals/SERVICES", admin, Map.of("first_response_minutes", 90)).status()).isEqualTo(200);
            assertThat(slaMinutes(serviceRequest(customer, addr, "NORMAL").path("id").asText())).isBetween(89L, 90L);
            // URGENT uses the shorter priority rule (30 minutes).
            assertThat(slaMinutes(serviceRequest(customer, addr, "URGENT").path("id").asText())).isBetween(29L, 30L);
            assertThat(api.put("/api/v1/admin/sla/verticals/SERVICES", admin, Map.of("first_response_minutes", 5)).status()).isEqualTo(400);
            assertThat(api.put("/api/v1/admin/sla/verticals/SERVICES", api.login("9000000011", "AGENT"), Map.of("first_response_minutes", 100)).status()).isEqualTo(403);

            // The primary's workspace shows the countdown; after the deadline the request escalates to the backup.
            String primary = link(customer, "PRIMARY"), backup = link(customer, "BACKUP");
            String primaryToken = api.login(phoneOfAgent(primary), "AGENT");
            assertThat(api.get("/api/v1/agent/workspace", primaryToken).data().path("sla_countdown").toString()).contains(r1);
            jdbc.sql("UPDATE service_requests SET sla_due_at = now() - interval '1 minute' WHERE id = ?::uuid").param(r1).update();
            assertThat(escalations.escalateBreached()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.sql("SELECT escalated_to_agent_id::text FROM service_requests WHERE id = ?::uuid").param(r1).query(String.class).single()).isEqualTo(backup);
            assertThat(api.get("/api/v1/agent/activity", api.login(phoneOfAgent(backup), "AGENT")).data().toString()).contains("ESCALATION_RECEIVED");
        } finally {
            api.put("/api/v1/admin/sla/verticals/SERVICES", admin, Map.of("first_response_minutes", 60));
        }
    }

    // ----- Drafts + M: the agent prepares, only the customer approves and pays ------------------------------------

    @Test
    void agentPreparesDraftsAndOnlyTheCustomerCanApproveThemOrPay() {
        String customer = subscribe(List.of("en", "te"), List.of());
        String addr = address(customer);
        String customerId = userId(customer);
        String agentToken = api.login(phoneOfAgent(link(customer, "PRIMARY")), "AGENT");

        // RFQ draft.
        Map<String, Object> rfq = Map.of("kind", "RFQ", "note", "As we discussed on the phone", "rfq", Map.of("category_id", categoryId("HARDWARE_SUPPLIES"),
                "address_id", addr, "fulfilment", "DELIVERY", "items", List.of(Map.of("name", "Wall putty 20 kg", "quantity", 4, "unit", "bags", "brand", "Birla"))));
        var d1 = api.post("/api/v1/agent/customers/" + customerId + "/drafts", agentToken, rfq);
        assertThat(d1.status()).as(d1.body().toString()).isEqualTo(200);
        assertThat(d1.data().path("status").asText()).isEqualTo("SENT");
        // Not for customers outside the agent's portfolio.
        String stranger = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String strangerId = userId(stranger);
        boolean strangersAgentIsMe = link(stranger, "PRIMARY") != null && link(stranger, "PRIMARY").equals(link(customer, "PRIMARY"));
        if (!strangersAgentIsMe) assertThat(api.post("/api/v1/agent/customers/" + strangerId + "/drafts", agentToken, rfq).status()).isEqualTo(403);

        // Booking draft (a free slot with a chosen professional).
        String slot = null;
        for (JsonNode d : api.get("/api/v1/customer/services/" + tapRepair() + "/slots?addressId=" + addr, customer).data().path("days")) {
            for (JsonNode s : d.path("slots")) {
                OffsetDateTime t = OffsetDateTime.parse(s.path("start").asText());
                if (slot == null && t.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).getDayOfWeek().getValue() != 7 && s.path("providers").asInt() >= 2) slot = s.path("start").asText();
            }
        }
        String plumber = api.get("/api/v1/customer/services/" + tapRepair() + "/providers?addressId=" + addr + "&slot=" + slot, customer).data().get(0).path("id").asText();
        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("service_item_id", tapRepair());
        booking.put("partner_id", plumber);
        booking.put("slot_start", slot);
        booking.put("address_id", addr);
        booking.put("answers", Map.of("problem_area", "Kitchen", "leak_severity", "Slow drip", "details", "Kitchen tap keeps dripping at night"));
        var d2 = api.post("/api/v1/agent/customers/" + customerId + "/drafts", agentToken, Map.of("kind", "BOOKING", "booking", booking));
        assertThat(d2.status()).as(d2.body().toString()).isEqualTo(200);
        assertThat(d2.data().path("estimated_total").decimalValue()).isPositive();

        // Purchase draft (one shop).
        String bulb = jdbc.sql("""
                SELECT l.id::text FROM vendor_listings l JOIN product_variants v ON v.id = l.variant_id JOIN partners p ON p.id = l.partner_id
                JOIN users u ON u.id = p.user_id WHERE u.phone = '+919000000026' AND v.sku = 'LED_BULB_9W_CDL'""").query(String.class).single();
        var d3 = api.post("/api/v1/agent/customers/" + customerId + "/drafts", agentToken,
                Map.of("kind", "PURCHASE", "purchase", Map.of("items", List.of(Map.of("listing_id", bulb, "quantity", 4)))));
        assertThat(d3.status()).as(d3.body().toString()).isEqualTo(200);
        assertThat(d3.data().path("preview").get(0).path("label").asText()).contains("4 x LED Bulb");
        var d4 = api.post("/api/v1/agent/customers/" + customerId + "/drafts", agentToken, rfq);

        // The customer sees what the agent prepared.
        JsonNode mine = api.get("/api/v1/customer/drafts?status=SENT", customer).data();
        assertThat(mine.size()).isEqualTo(4);

        // M: an agent can never approve, accept a quote or pay on the customer's behalf.
        String draft1 = d1.data().path("id").asText();
        assertThat(api.post("/api/v1/customer/drafts/" + draft1 + "/approve", agentToken, Map.of()).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/customer/requests/" + draft1 + "/quotations/" + draft1 + "/accept", agentToken, Map.of("payment_method", "ONLINE"), ApiClient.key()).status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/customer/payments/" + draft1 + "/simulate", agentToken, Map.of("outcome", "SUCCESS")).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/customer/orders", agentToken, Map.of("payment_method", "ONLINE"), ApiClient.key()).status()).isEqualTo(403);
        // Another customer can't approve it either.
        assertThat(api.post("/api/v1/customer/drafts/" + draft1 + "/approve", stranger, Map.of()).status()).isEqualTo(404);

        // The customer approves: RFQ -> quotation request sent to shops.
        var a1 = api.post("/api/v1/customer/drafts/" + draft1 + "/approve", customer, Map.of());
        assertThat(a1.status()).as(a1.body().toString()).isEqualTo(200);
        String rfqRequest = a1.data().path("result_request_id").asText();
        assertThat(jdbc.sql("SELECT request_type FROM service_requests WHERE id = ?::uuid").param(rfqRequest).query(String.class).single()).isEqualTo("PRODUCT_RFQ");
        assertThat(jdbc.sql("SELECT reason FROM request_status_history WHERE request_id = ?::uuid AND reason LIKE 'Prepared by agent%'").param(rfqRequest)
                .query(String.class).list()).hasSize(1);
        assertThat(api.post("/api/v1/customer/drafts/" + draft1 + "/approve", customer, Map.of()).status()).isEqualTo(409);

        // Booking -> booked with the professional, paid after the service.
        var a2 = api.post("/api/v1/customer/drafts/" + d2.data().path("id").asText() + "/approve", customer, Map.of("payment_method", "PAY_ON_DELIVERY"));
        assertThat(a2.status()).as(a2.body().toString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(a2.data().path("result_request_id").asText()).query(String.class).single())
                .isEqualTo("PARTNER_ASSIGNED");

        // Purchase -> items in the customer's cart; the customer checks out and pays themselves.
        var a3 = api.post("/api/v1/customer/drafts/" + d3.data().path("id").asText() + "/approve", customer, Map.of());
        assertThat(a3.data().path("next").asText()).isEqualTo("PRODUCT_CART");
        assertThat(api.get("/api/v1/customer/product-cart", customer).data().path("item_count").asInt()).isEqualTo(4);

        // Reject one; expired drafts can't be approved.
        String draft4 = d4.data().path("id").asText();
        jdbc.sql("UPDATE agent_drafts SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid").param(draft4).update();
        assertThat(api.post("/api/v1/customer/drafts/" + draft4 + "/approve", customer, Map.of()).status()).isEqualTo(409);
        assertThat(api.get("/api/v1/customer/drafts/" + draft4, customer).data().path("status").asText()).isEqualTo("EXPIRED");

        // The agent's activity log and workspace reflect it all; manual vendor follow-up notes are logged too.
        String sriSai = jdbc.sql("SELECT p.id::text FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = '+919000000026'").query(String.class).single();
        assertThat(api.post("/api/v1/agent/activity", agentToken, Map.of("kind", "VENDOR_FOLLOW_UP", "summary", "Called Sri Sai about the putty quote",
                "request_id", rfqRequest, "partner_id", sriSai)).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/agent/activity", agentToken, Map.of("kind", "DRAFT_APPROVED", "summary", "fake")).status()).isEqualTo(400);
        List<String> kinds = new ArrayList<>();
        api.get("/api/v1/agent/activity?customerId=" + customerId, agentToken).data().forEach(x -> kinds.add(x.path("kind").asText()));
        assertThat(kinds).contains("DRAFT_SENT", "DRAFT_APPROVED", "VENDOR_FOLLOW_UP");
        JsonNode ws = api.get("/api/v1/agent/workspace", agentToken).data();
        assertThat(ws.path("metrics_30d").path("drafts_approved").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(ws.path("by_vertical").size()).isGreaterThanOrEqualTo(4);
        assertThat(ws.path("queues").has("vendor_follow_ups")).isTrue();
    }
}
