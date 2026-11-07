package com.buzzer;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Cart -> checkout (book now / send to agent), wishlist and write-to-agent. */
@AutoConfigureMockMvc
class CartCheckoutTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    ApiClient api;
    String customer;
    String address;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
        customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        address = api.post("/api/v1/customer/addresses", customer, Map.of("label", "HOME", "line1", "Flat 9, Sunrise Towers",
                "city_id", city, "pincode", "500081")).data().path("id").asText();
    }

    private String item(String sub, String code) {
        return jdbc.sql("SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id WHERE s.code = ? AND i.code = ?")
                .param(sub).param(code).query(String.class).single();
    }

    @Test
    void richMenusExistWithPricesAndContextualSubcategories() {
        String electrician = jdbc.sql("SELECT id::text FROM subcategories WHERE code = 'ELECTRICAL'").query(String.class).single();
        JsonNode items = api.get("/api/v1/catalogue/subcategories/" + electrician + "/services", null).data();
        List<String> names = new ArrayList<>();
        items.forEach(i -> names.add(i.path("name").asText()));
        assertThat(names).contains("Fan Repair", "Light / Tube Light Repair", "Wiring & Rewiring", "Combo Switch Board Installation");
        String wedding = jdbc.sql("SELECT id::text FROM categories WHERE code = 'WEDDING_CATERING'").query(String.class).single();
        List<String> subs = new ArrayList<>();
        api.get("/api/v1/catalogue/categories/" + wedding + "/subcategories", null).data().forEach(s -> subs.add(s.path("name").asText()));
        assertThat(subs).contains("Wedding", "Haldi", "Mehendi", "Half Saree Function", "Birthday Function", "Catering");
        String travel = jdbc.sql("SELECT id::text FROM categories WHERE code = 'TRAVEL_TRANSPORT'").query(String.class).single();
        List<String> travelSubs = new ArrayList<>();
        api.get("/api/v1/catalogue/categories/" + travel + "/subcategories", null).data().forEach(s -> travelSubs.add(s.path("name").asText()));
        assertThat(travelSubs).contains("Book a Ticket", "Hotels & Stays", "Holiday Packages", "International Trips");
        assertThat(api.get("/api/v1/catalogue/deals", null).data().size()).isGreaterThan(0); // dev seed deals
    }

    @Test
    void cartTotalsAreComputedOnTheServerAndCheckoutSplitsBySubcategory() {
        String fan = item("ELECTRICAL", "FAN_REPAIR");          // 149 starting-from
        String kitchen = item("CLEANING", "KITCHEN_CLEAN");    // 1299 fixed (dev deal: was 1599)
        String bus = item("BOOK_TICKETS", "BUS_BOOKING");      // quote

        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", fan, "quantity", 1));
        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", fan, "quantity", 1)); // adds up to 2
        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", kitchen));
        JsonNode cart = api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", bus)).data();

        assertThat(cart.path("item_count").asInt()).isEqualTo(4);
        assertThat(cart.path("estimated_total").decimalValue()).isEqualByComparingTo("1597.00"); // 2 x 149 + 1299
        assertThat(cart.path("savings").decimalValue()).isEqualByComparingTo("300");
        assertThat(cart.path("has_quote_items").asBoolean()).isTrue();
        assertThat(cart.path("has_estimates").asBoolean()).isTrue();
        assertThat(cart.path("request_count").asInt()).isEqualTo(3);

        cart = api.put("/api/v1/customer/cart/items/" + fan, customer, Map.of("quantity", 3)).data();
        assertThat(cart.path("estimated_total").decimalValue()).isEqualByComparingTo("1746.00");

        String key = ApiClient.key();
        var checkout = api.post("/api/v1/customer/cart/checkout", customer, Map.of("mode", "SELF", "address_id", address,
                "note", "Please come after 5pm"), key);
        assertThat(checkout.status()).isEqualTo(200);
        assertThat(checkout.data().size()).isEqualTo(3);
        // Cart is emptied; a retried checkout returns the same requests instead of creating new ones.
        assertThat(api.get("/api/v1/customer/cart", customer).data().path("item_count").asInt()).isZero();
        var retry = api.post("/api/v1/customer/cart/checkout", customer, Map.of("mode", "SELF", "address_id", address), key);
        assertThat(retry.data().get(0).path("id").asText()).isEqualTo(checkout.data().get(0).path("id").asText());

        var row = jdbc.sql("""
                SELECT r.booking_mode, r.estimated_total::text AS est, r.has_quote_items, jsonb_array_length(r.line_items) AS lines, r.description
                FROM service_requests r JOIN subcategories s ON s.id = r.subcategory_id WHERE r.id IN (?::uuid, ?::uuid, ?::uuid) AND s.code = 'ELECTRICAL'""")
                .param(checkout.data().get(0).path("id").asText()).param(checkout.data().get(1).path("id").asText())
                .param(checkout.data().get(2).path("id").asText()).query().singleRow();
        assertThat(row.get("booking_mode")).isEqualTo("SELF");
        assertThat(row.get("est")).isEqualTo("447.00");
        assertThat(row.get("lines")).isEqualTo(1);
        assertThat((String) row.get("description")).contains("3 x Fan Repair").contains("after 5pm");

        // The agent sees the line items; the electrician (and only an electrician) is eligible.
        String electricalRequest = jdbc.sql("""
                SELECT r.id::text FROM service_requests r JOIN subcategories s ON s.id = r.subcategory_id
                WHERE s.code = 'ELECTRICAL' AND r.idempotency_key LIKE ?""").param(key + "#%").query(String.class).single();
        String agentPhone = jdbc.sql("""
                SELECT substring(u.phone from 4) FROM service_requests r JOIN agents a ON a.id = r.agent_id JOIN users u ON u.id = a.user_id WHERE r.id = ?::uuid""")
                .param(electricalRequest).query(String.class).single();
        String agent = api.login(agentPhone, "AGENT");
        JsonNode detail = api.get("/api/v1/agent/requests/" + electricalRequest, agent).data();
        assertThat(detail.path("booking_mode").asText()).isEqualTo("SELF");
        assertThat(detail.path("line_items").get(0).path("name").asText()).isEqualTo("Fan Repair");
        List<String> eligible = new ArrayList<>();
        api.get("/api/v1/agent/requests/" + electricalRequest + "/eligible-partners", agent).data().forEach(p -> eligible.add(p.path("display_name").asText()));
        assertThat(eligible).contains("Bright Spark Electricals").doesNotContain("Quick Fix Plumbers");
    }

    @Test
    void formOnlyServicesAndOtherItemsCannotBeAddedToTheCart() {
        String other = item("ELECTRICAL", "OTHER_ELECTRICAL");
        assertThat(api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", other)).errorCode()).isEqualTo("VALIDATION_FAILED");
        String rent = item("RENT", "RENT_REQUIREMENT");
        assertThat(api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", rent)).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/api/v1/customer/cart/checkout", customer, Map.of("mode", "SELF", "address_id", address), ApiClient.key()).errorCode())
                .isEqualTo("VALIDATION_FAILED"); // empty cart
    }

    @Test
    void sendToAgentAndWriteToAgentModes() {
        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", item("HOLIDAY_PACKAGES", "GOA_PKG"), "quantity", 2));
        var sent = api.post("/api/v1/customer/cart/checkout", customer, Map.of("mode", "AGENT_ASSISTED", "address_id", address,
                "note", "Prefer December, beach-facing hotel"), ApiClient.key());
        assertThat(sent.data().size()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT booking_mode FROM service_requests WHERE id = ?::uuid").param(sent.data().get(0).path("id").asText())
                .query(String.class).single()).isEqualTo("AGENT_ASSISTED");

        var direct = api.post("/api/v1/customer/agent-request", customer, Map.of("message", "Please find a good tailor near Kondapur for my blazer",
                "address_id", address), ApiClient.key());
        assertThat(direct.status()).isEqualTo(200);
        var row = jdbc.sql("""
                SELECT r.booking_mode, c.code FROM service_requests r JOIN categories c ON c.id = r.category_id WHERE r.id = ?::uuid""")
                .param(direct.data().path("id").asText()).query().singleRow();
        assertThat(row.get("booking_mode")).isEqualTo("AGENT_DIRECT");
        assertThat(row.get("code")).isEqualTo("SPECIAL_REQUESTS");
        assertThat(api.post("/api/v1/customer/agent-request", customer, Map.of("message", "hi"), null).errorCode()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void wishlistAddListRemove() {
        String facial = item("SALON_AT_HOME", "FACIAL");
        assertThat(api.put("/api/v1/customer/wishlist/" + facial, customer, Map.of()).status()).isEqualTo(200);
        api.put("/api/v1/customer/wishlist/" + facial, customer, Map.of()); // idempotent
        JsonNode list = api.get("/api/v1/customer/wishlist", customer).data();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("name").asText()).isEqualTo("Facial & Cleanup");
        assertThat(api.get("/api/v1/customer/wishlist/ids", customer).data().get(0).asText()).isEqualTo(facial);
        api.call("DELETE", "/api/v1/customer/wishlist/" + facial, customer, null, null);
        assertThat(api.get("/api/v1/customer/wishlist", customer).data().size()).isZero();
    }
}
