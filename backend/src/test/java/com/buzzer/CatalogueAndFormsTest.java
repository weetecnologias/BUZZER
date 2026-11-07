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

@AutoConfigureMockMvc
class CatalogueAndFormsTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
    }

    private String id(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    @Test
    void launchCatalogueHasAll25CategoriesInOrder() {
        JsonNode cats = api.get("/api/v1/catalogue/categories", null).data();
        assertThat(cats.size()).isGreaterThanOrEqualTo(25);
        assertThat(cats.get(0).path("name").asText()).isEqualTo("Real Estate & Property");
        assertThat(cats.get(1).path("name").asText()).isEqualTo("Home Repairs & Maintenance");
    }

    @Test
    void contextualNavigationShowsOnlyPlumbingServicesUnderPlumbing() {
        String plumbing = id("SELECT id::text FROM subcategories WHERE code = 'PLUMBING'");
        JsonNode services = api.get("/api/v1/catalogue/subcategories/" + plumbing + "/services", null).data();
        List<String> names = new ArrayList<>();
        services.forEach(s -> names.add(s.path("name").asText()));
        assertThat(names).contains("Tap Repair", "Pipe Leakage", "Bathroom Fittings", "Pipeline Installation", "Other Plumbing Requirement");
        assertThat(names).doesNotContain("Fan Installation", "AC Repair");
        assertThat(names.getLast()).isEqualTo("Other Plumbing Requirement");
    }

    @Test
    void currentLocationResolvesToNearestServiceableLocality() {
        JsonNode near = api.get("/api/v1/locations/nearest?lat=17.4620&lng=78.3650", null).data();
        assertThat(near.path("in_service_area").asBoolean()).isTrue();
        assertThat(near.path("city_name").asText()).isEqualTo("Hyderabad");
        assertThat(near.path("locality_name").asText()).isEqualTo("Kondapur");
        assertThat(near.path("pincode").asText()).isEqualTo("500084");

        JsonNode far = api.get("/api/v1/locations/nearest?lat=28.6139&lng=77.2090", null).data(); // New Delhi
        assertThat(far.path("in_service_area").asBoolean()).isFalse();
        assertThat(far.has("locality_id")).isFalse();
        assertThat(api.get("/api/v1/locations/nearest?lat=123&lng=78", null).errorCode()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void categoryWideServiceListTagsEachServiceWithItsSubcategory() {
        String homeRepairs = id("SELECT id::text FROM categories WHERE code = 'HOME_REPAIRS'");
        JsonNode all = api.get("/api/v1/catalogue/categories/" + homeRepairs + "/services", null).data();
        List<String> subs = new ArrayList<>();
        all.forEach(s -> subs.add(s.path("subcategory_name").asText()));
        assertThat(subs).contains("Plumber", "Electrician", "AC Repair & Service", "Cleaning");
        assertThat(subs).doesNotContain("Rent / Lease");
    }

    @Test
    void serverRejectsInvalidDynamicFormsAndMismatchedHierarchy() {
        String token = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String city = id("SELECT id::text FROM cities WHERE code = 'HYD'");
        String address = api.post("/api/v1/customer/addresses", token, Map.of("label", "HOME", "line1", "Flat 101, Green Residency",
                "city_id", city, "pincode", "500084")).data().path("id").asText();
        String leakage = id("SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id WHERE s.code='PLUMBING' AND i.code='PIPE_LEAKAGE'");
        String electrical = id("SELECT id::text FROM subcategories WHERE code = 'ELECTRICAL'");

        var missing = api.post("/api/v1/customer/requests", token, Map.of("service_item_id", leakage, "address_id", address,
                "answers", Map.of("problem_area", "Kitchen")));
        assertThat(missing.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.body().path("error").path("details").path("fields").has("leak_severity")).isTrue();

        var badOption = api.post("/api/v1/customer/requests", token, Map.of("service_item_id", leakage, "address_id", address,
                "answers", Map.of("problem_area", "Moon", "leak_severity", "Slow drip", "details", "Leak under the sink for two days")));
        assertThat(badOption.errorCode()).isEqualTo("VALIDATION_FAILED");

        var mismatch = api.post("/api/v1/customer/requests", token, Map.of("service_item_id", leakage, "subcategory_id", electrical,
                "address_id", address, "answers", Map.of()));
        assertThat(mismatch.errorCode()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void adminCanAddCategoryWithoutDeploymentAndItAppearsWithAnOtherItem() {
        String admin = api.login("9000000001", "ADMIN");
        String code = "PET_CARE_" + System.nanoTime() % 100000;
        var created = api.post("/api/v1/admin/catalogue/categories", admin, Map.of("code", code, "name", "Pet Care " + code,
                "workflow_template", "MENU_DRIVEN"));
        assertThat(created.status()).isEqualTo(200);
        String catId = created.data().path("id").asText();
        var sub = api.post("/api/v1/admin/catalogue/categories/" + catId + "/subcategories", admin, Map.of("code", "GROOMING", "name", "Pet Grooming"));
        assertThat(sub.status()).isEqualTo(200);
        JsonNode services = api.get("/api/v1/catalogue/subcategories/" + sub.data().path("id").asText() + "/services", null).data();
        assertThat(services.get(0).path("name").asText()).isEqualTo("Other Pet Grooming Requirement");
        JsonNode cats = api.get("/api/v1/catalogue/categories", null).data();
        boolean found = false;
        for (JsonNode c : cats) found |= c.path("code").asText().equals(code);
        assertThat(found).isTrue();

        var badSchema = api.post("/api/v1/admin/catalogue/subcategories/" + sub.data().path("id").asText() + "/services", admin,
                Map.of("code", "BATH", "name", "Bath", "form_schema", Map.of("fields", List.of(Map.of("key", "x", "type", "unknown", "label", "X")))));
        assertThat(badSchema.errorCode()).isEqualTo("VALIDATION_FAILED");
    }
}
