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

/** Stage 3: business verticals, admin on/off switches and universal search routing. */
@AutoConfigureMockMvc
class PlatformVerticalsTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
        jdbc.sql("UPDATE platform_verticals SET enabled = TRUE").update();
    }

    private List<String> codes(JsonNode list, String field) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    @Test
    void verticalsAreListedPublicallyAndCategoriesAreTagged() {
        JsonNode verticals = api.get("/api/v1/platform/verticals", null).data();
        assertThat(codes(verticals, "code")).containsExactly("MARKETPLACE", "SERVICES", "TASKS", "ASSISTANCE");
        assertThat(codes(api.get("/api/v1/catalogue/categories?vertical=MARKETPLACE", null).data(), "code")).contains("HARDWARE_SUPPLIES");
        assertThat(codes(api.get("/api/v1/catalogue/categories?vertical=TASKS", null).data(), "code")).contains("DAILY_ERRANDS", "DELIVERY");
        assertThat(codes(api.get("/api/v1/catalogue/categories?vertical=SERVICES", null).data(), "code")).contains("HOME_REPAIRS")
                .doesNotContain("HARDWARE_SUPPLIES");
        JsonNode shops = api.get("/api/v1/platform/featured-shops", null).data();
        assertThat(codes(shops, "display_name")).contains("Sri Sai Hardware");
    }

    @Test
    void adminCanSwitchAVerticalOffAndItDisappearsEverywhereWithoutLosingData() {
        String admin = api.login("9000000001", "ADMIN");
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String hardware = jdbc.sql("SELECT id::text FROM categories WHERE code = 'HARDWARE_SUPPLIES'").query(String.class).single();
        String bulb = jdbc.sql("SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id WHERE i.code = 'LED_BULB_9W'")
                .query(String.class).single();
        try {
            // Only admins (not agents/partners) may switch.
            String agent = api.login("9000000011", "AGENT");
            assertThat(api.put("/api/v1/admin/platform/verticals/MARKETPLACE", agent, Map.of("enabled", false)).status()).isEqualTo(403);

            var off = api.put("/api/v1/admin/platform/verticals/MARKETPLACE", admin, Map.of("enabled", false));
            assertThat(off.status()).as(off.body().toString()).isEqualTo(200);
            assertThat(off.data().path("enabled").asBoolean()).isFalse();

            assertThat(codes(api.get("/api/v1/platform/verticals", null).data(), "code")).doesNotContain("MARKETPLACE");
            assertThat(codes(api.get("/api/v1/catalogue/categories", null).data(), "code")).doesNotContain("HARDWARE_SUPPLIES").contains("HOME_REPAIRS");
            assertThat(api.get("/api/v1/catalogue/categories/" + hardware, null).status()).isEqualTo(404);
            assertThat(api.get("/api/v1/catalogue/search?q=LED", null).data().size()).isZero();
            assertThat(api.get("/api/v1/platform/featured-shops", null).data().size()).isZero();
            var add = api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", bulb, "quantity", 1));
            assertThat(add.status()).isEqualTo(400);
            // Admin still sees it (and the data is intact).
            JsonNode adminList = api.get("/api/v1/admin/platform/verticals", admin).data();
            assertThat(codes(adminList, "code")).contains("MARKETPLACE");
            assertThat(jdbc.sql("SELECT count(*) FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id WHERE c.code = 'HARDWARE_SUPPLIES'")
                    .query(Integer.class).single()).isGreaterThan(20);
        } finally {
            api.put("/api/v1/admin/platform/verticals/MARKETPLACE", admin, Map.of("enabled", true));
        }
        assertThat(api.get("/api/v1/catalogue/categories/" + hardware, null).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", bulb, "quantity", 1)).status()).isEqualTo(200);
    }

    @Test
    void universalSearchSuggestsTheRightWorkflowWithoutCreatingAnything() {
        long before = jdbc.sql("SELECT count(*) FROM service_requests").query(Long.class).single();

        JsonNode tap = api.get("/api/v1/search?q=leaking tap repair", null).data();
        assertThat(tap.path("suggestion").path("workflow").asText()).isEqualTo("BOOK_SERVICE");
        assertThat(tap.path("items").size()).isGreaterThan(0);

        JsonNode bulk = api.get("/api/v1/search?q=50 bags wall putty bulk", null).data();
        assertThat(bulk.path("suggestion").path("workflow").asText()).isEqualTo("REQUEST_QUOTES");
        assertThat(bulk.path("suggestion").path("vertical").asText()).isEqualTo("MARKETPLACE");

        JsonNode task = api.get("/api/v1/search?q=pickup documents and deliver to office", null).data();
        assertThat(task.path("suggestion").path("workflow").asText()).isEqualTo("TASK");

        JsonNode shop = api.get("/api/v1/search?q=Sri Sai", null).data();
        assertThat(codes(shop.path("shops"), "display_name")).as(shop.toString()).contains("Sri Sai Hardware");

        JsonNode vague = api.get("/api/v1/search?q=please plan my mother's birthday", null).data();
        assertThat(vague.path("suggestion").path("workflow").asText()).isEqualTo("ASSISTANCE");

        assertThat(jdbc.sql("SELECT count(*) FROM service_requests").query(Long.class).single()).isEqualTo(before);
    }
}
