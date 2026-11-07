package com.buzzer;

import com.buzzer.quotation.DirectQuoteService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Get quotes": cart (menu + extra items) goes straight to verified shops, they quote per item, the quote reaches the
 * customer automatically, the customer asks for a revision, approves and pays - no agent work. Agents only watch
 * (subscribers) or take over automatically when nobody can quote.
 */
@AutoConfigureMockMvc
class DirectQuoteFlowTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired DirectQuoteService directQuotes;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
        jdbc.sql("UPDATE partners SET availability = 'AVAILABLE'").update();
        jdbc.sql("UPDATE agents SET availability = 'AVAILABLE'").update();
    }

    private String item(String code) {
        return jdbc.sql("""
                SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                WHERE c.code = 'HARDWARE_SUPPLIES' AND i.code = ?""").param(code).query(String.class).single();
    }

    private String address(String token) {
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        return api.post("/api/v1/customer/addresses", token, Map.of("label", "HOME", "line1", "Plot 7, Road 2, Kukatpally",
                "city_id", city, "pincode", "500072")).data().path("id").asText();
    }

    /** Cart: 2 bulbs + 1 MCB (electrical supplies) plus an extra item that is not on the menu. */
    private JsonNode checkoutElectrical(String customer, String mode) {
        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", item("LED_BULB_9W"), "quantity", 2));
        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", item("MCB_16A"), "quantity", 1));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("address_id", address(customer));
        body.put("note", "Deliver after 5 pm");
        body.put("extra_items", List.of(Map.of("name", "Ceiling rose (white)", "quantity", 3, "note", "Any brand")));
        var res = api.post("/api/v1/customer/cart/checkout", customer, body, ApiClient.key());
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.data().get(0);
    }

    private String invitationFor(String partnerToken, String requestId) {
        for (JsonNode l : api.get("/api/v1/partner/leads", partnerToken).data()) {
            if (l.path("request_id").asText().equals(requestId)) return l.path("invitation_id").asText();
        }
        throw new AssertionError("No lead for request " + requestId);
    }

    /** Quotes every line: the extra item is marked unavailable when {@code extraAvailable} is false. */
    private JsonNode quote(String partner, String invitationId, boolean extraAvailable, int deliveryFee, int bulbPrice) {
        JsonNode lead = api.get("/api/v1/partner/leads/" + invitationId, partner).data();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode l : lead.path("line_items")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", l.path("key").asText());
            boolean custom = l.path("custom").asBoolean();
            m.put("available", !custom || extraAvailable);
            if (custom) m.put("unit_price", 35);
            else if (l.path("name").asText().startsWith("LED")) m.put("unit_price", bulbPrice);
            else m.put("unit_price", l.path("suggested_price").decimalValue());
            lines.add(m);
        }
        var res = api.post("/api/v1/partner/leads/" + invitationId + "/item-quote", partner,
                Map.of("lines", lines, "delivery_fee", deliveryFee, "partner_note", "Branded items, GST bill", "pay_on_delivery_allowed", true));
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.data();
    }

    @Test
    void exploringCustomerGetsQuotesStraightFromShopsAndApprovesWithoutAnyAgent() {
        String customer = api.loginOnly(ApiClient.randomPhone(), "CUSTOMER");
        api.post("/api/v1/customer/onboarding/explore", customer, Map.of());

        // Non-subscribers cannot use "Send to my agent".
        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", item("HAMMER"), "quantity", 1));
        var agentMode = api.post("/api/v1/customer/cart/checkout", customer, Map.of("mode", "AGENT_ASSISTED", "address_id", address(customer)), ApiClient.key());
        assertThat(agentMode.status()).isEqualTo(400);
        api.call("DELETE", "/api/v1/customer/cart", customer, null, null);

        JsonNode created = checkoutElectrical(customer, "DIRECT_QUOTE");
        String requestId = created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("PARTNER_QUOTATION");
        var row = jdbc.sql("SELECT handling_mode, agent_id IS NULL AS no_agent, agent_source, jsonb_array_length(line_items) AS lines FROM service_requests WHERE id = ?::uuid")
                .param(requestId).query().singleRow();
        assertThat(row.get("handling_mode")).isEqualTo("AUTOMATED");
        assertThat(row.get("no_agent")).isEqualTo(true);
        assertThat(row.get("agent_source")).isEqualTo("NONE");
        assertThat(row.get("lines")).isEqualTo(3);

        // Both electrical suppliers were invited automatically; the plumbers were not.
        List<String> invited = jdbc.sql("""
                SELECT p.display_name FROM partner_invitations i JOIN partners p ON p.id = i.partner_id WHERE i.request_id = ?::uuid ORDER BY 1""")
                .param(requestId).query(String.class).list();
        assertThat(invited).containsExactly("Bright Spark Electricals", "Sri Sai Hardware");

        // Shop sees every item with its own approved price pre-filled, and the customer's extra item.
        String shop = api.login("9000000026", "PARTNER");
        String inv = invitationFor(shop, requestId);
        JsonNode lead = api.get("/api/v1/partner/leads/" + inv, shop).data();
        assertThat(lead.path("can_quote").asBoolean()).isTrue();
        JsonNode bulb = null, extra = null;
        for (JsonNode l : lead.path("line_items")) {
            if (l.path("name").asText().startsWith("LED")) bulb = l;
            if (l.path("custom").asBoolean()) extra = l;
        }
        assertThat(bulb.path("suggested_price").decimalValue()).isEqualByComparingTo("94"); // 95% of 99, rounded
        assertThat(extra.path("name").asText()).isEqualTo("Ceiling rose (white)");
        assertThat(extra.path("customer_note").asText()).isEqualTo("Any brand");

        // Quote: bulbs 94 x2, MCB 247 (own price), extra not available, delivery 40 -> server total 188 + 247 + 40 = 475.
        JsonNode q1 = quote(shop, inv, false, 40, 94);
        assertThat(q1.path("total_amount").decimalValue()).isEqualByComparingTo("475");
        assertThat(q1.path("status").asText()).isEqualTo("SHARED");
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(requestId).query(String.class).single())
                .isEqualTo("CUSTOMER_APPROVAL");

        // Customer sees it immediately with per-item availability, and asks for a revision.
        JsonNode detail = api.get("/api/v1/customer/requests/" + requestId, customer).data();
        JsonNode quotes = detail.path("quotations");
        assertThat(quotes.size()).isEqualTo(1);
        assertThat(quotes.get(0).path("line_items").size()).isEqualTo(3);
        String q1Id = quotes.get(0).path("id").asText();
        var revise = api.post("/api/v1/customer/requests/" + requestId + "/quotations/" + q1Id + "/revise", customer,
                Map.of("note", "Please include the ceiling roses"));
        assertThat(revise.status()).as(revise.body().toString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(requestId).query(String.class).single())
                .isEqualTo("PARTNER_QUOTATION");
        assertThat(api.get("/api/v1/partner/leads/" + inv, shop).data().path("my_quote").path("revision_note").asText())
                .isEqualTo("Please include the ceiling roses");

        // Shop revises: extra now available at 35 x 3 -> 580. Old version is kept as REVISED.
        JsonNode q2 = quote(shop, inv, true, 40, 94);
        assertThat(q2.path("revision_no").asInt()).isEqualTo(2);
        assertThat(q2.path("total_amount").decimalValue()).isEqualByComparingTo("580");
        assertThat(jdbc.sql("SELECT status FROM quotations WHERE id = ?::uuid").param(q1Id).query(String.class).single()).isEqualTo("REVISED");

        // Customer approves (pay on delivery) -> exactly one partner assigned.
        String q2Id = q2.path("id").asText();
        var accept = api.post("/api/v1/customer/requests/" + requestId + "/quotations/" + q2Id + "/accept", customer,
                Map.of("payment_method", "PAY_ON_DELIVERY"), ApiClient.key());
        assertThat(accept.status()).as(accept.body().toString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM assignments WHERE request_id = ?::uuid AND status = 'ACTIVE'").param(requestId)
                .query(Integer.class).single()).isEqualTo(1);

        // The whole journey is in the timeline, with the automatic steps marked SYSTEM.
        List<String> timeline = jdbc.sql("SELECT actor_role || ': ' || reason FROM request_status_history WHERE request_id = ?::uuid ORDER BY created_at, id")
                .param(requestId).query(String.class).list();
        assertThat(timeline.get(0)).startsWith("CUSTOMER: Customer asked verified partners for quotes");
        assertThat(timeline.get(1)).startsWith("SYSTEM: Sent automatically to 2 verified partner(s)");
        assertThat(timeline).anyMatch(t -> t.contains("Quote from Sri Sai Hardware") && t.contains("2/3 items available"));
        assertThat(timeline).anyMatch(t -> t.startsWith("CUSTOMER: Customer asked Sri Sai Hardware to revise"));
        assertThat(timeline).anyMatch(t -> t.contains("Revised quote (v2) from Sri Sai Hardware") && t.contains("3/3 items available"));
    }

    @Test
    void subscribersAgentWatchesAutomatedTicketsWithANewHighlight() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER"); // login() subscribes new customers
        JsonNode created = checkoutElectrical(customer, "DIRECT_QUOTE");
        String requestId = created.path("id").asText();
        var row = jdbc.sql("""
                SELECT r.handling_mode, r.agent_seen_at IS NULL AS unseen, substring(u.phone from 4) AS agent_phone
                FROM service_requests r JOIN agents a ON a.id = r.agent_id JOIN users u ON u.id = a.user_id WHERE r.id = ?::uuid""")
                .param(requestId).query().singleRow();
        assertThat(row.get("handling_mode")).isEqualTo("AUTOMATED");
        assertThat(row.get("unseen")).isEqualTo(true);

        String agent = api.login((String) row.get("agent_phone"), "AGENT");
        JsonNode inbox = api.get("/api/v1/agent/requests", agent).data();
        JsonNode mine = null;
        for (JsonNode r : inbox) if (r.path("id").asText().equals(requestId)) mine = r;
        assertThat(mine).isNotNull();
        assertThat(mine.path("handling_mode").asText()).isEqualTo("AUTOMATED");
        assertThat(mine.path("agent_seen_at").isNull()).isTrue();
        assertThat(mine.path("item_count").asInt()).isEqualTo(3);

        // Opening the ticket clears the NEW flag; the agent can take over if they want.
        api.get("/api/v1/agent/requests/" + requestId, agent);
        assertThat(jdbc.sql("SELECT agent_seen_at IS NOT NULL FROM service_requests WHERE id = ?::uuid").param(requestId).query(Boolean.class).single()).isTrue();
        assertThat(api.post("/api/v1/agent/requests/" + requestId + "/take-over", agent, Map.of()).data().path("handling_mode").asText()).isEqualTo("AGENT");
    }

    @Test
    void requestsNobodyQuotesAreHandedToAnAgentAutomatically() {
        String customer = api.loginOnly(ApiClient.randomPhone(), "CUSTOMER");
        api.post("/api/v1/customer/onboarding/explore", customer, Map.of());
        String requestId = checkoutElectrical(customer, "DIRECT_QUOTE").path("id").asText();

        // Both partners decline -> an on-duty agent takes over at once.
        for (String phone : List.of("9000000026", "9000000024")) {
            String p = api.login(phone, "PARTNER");
            api.post("/api/v1/partner/leads/" + invitationFor(p, requestId) + "/decline", p, Map.of("reason", "Out of stock"));
        }
        var row = jdbc.sql("SELECT handling_mode, agent_id IS NOT NULL AS has_agent, agent_source, handover_reason FROM service_requests WHERE id = ?::uuid")
                .param(requestId).query().singleRow();
        assertThat(row.get("handling_mode")).isEqualTo("AGENT");
        assertThat(row.get("has_agent")).isEqualTo(true);
        assertThat(row.get("agent_source")).isEqualTo("ON_DUTY");
        assertThat(row.get("handover_reason")).isEqualTo("All invited partners declined");

        // No quote within the time limit -> handed over by the sweep.
        String customer2 = api.loginOnly(ApiClient.randomPhone(), "CUSTOMER");
        api.post("/api/v1/customer/onboarding/explore", customer2, Map.of());
        String r2 = checkoutElectrical(customer2, "DIRECT_QUOTE").path("id").asText();
        jdbc.sql("UPDATE service_requests SET created_at = now() - interval '3 hours' WHERE id = ?::uuid").param(r2).update();
        assertThat(directQuotes.handOverUnquoted()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT handling_mode FROM service_requests WHERE id = ?::uuid").param(r2).query(String.class).single()).isEqualTo("AGENT");
        assertThat(jdbc.sql("SELECT count(*) FROM ops_exceptions WHERE exception_type = 'DIRECT_QUOTE_HANDOVER' AND ref_id = ?::uuid")
                .param(r2).query(Integer.class).single()).isEqualTo(1);
    }
}
