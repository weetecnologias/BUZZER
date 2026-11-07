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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Welcome screen choice: subscribe for a language-matched dedicated agent, or explore with on-duty agents. */
@AutoConfigureMockMvc
class AgentSubscriptionTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
        // Priya Nair is the only non-manager agent who speaks Malayalam; make sure she can take customers.
        jdbc.sql("UPDATE agents SET availability = 'AVAILABLE', max_customers = 1000 WHERE display_name = 'Priya Nair'").update();
    }

    private String createSpecialRequest(String token) {
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        String address = api.post("/api/v1/customer/addresses", token, Map.of("label", "HOME", "line1", "Flat 3, Lake View, Kondapur",
                "city_id", city, "pincode", "500084")).data().path("id").asText();
        String special = jdbc.sql("SELECT id::text FROM categories WHERE code = 'SPECIAL_REQUESTS'").query(String.class).single();
        var r = api.post("/api/v1/customer/requests", token, Map.of("category_id", special, "address_id", address,
                "answers", Map.of("details", "Please arrange flowers for my mother's birthday", "urgency", "Flexible")));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.data().path("id").asText();
    }

    private int links(String token) {
        String userId = api.get("/api/v1/auth/me", token).data().path("id").asText();
        return jdbc.sql("SELECT count(*) FROM customer_agent_links WHERE customer_user_id = ?::uuid AND active").param(userId).query(Integer.class).single();
    }

    @Test
    void newCustomerSeesWelcomeChoiceAndNoAgentYet() {
        String customer = api.loginOnly(ApiClient.randomPhone(), "CUSTOMER");
        JsonNode st = api.get("/api/v1/customer/onboarding", customer).data();
        assertThat(st.path("onboarded").asBoolean()).isFalse();
        assertThat(st.path("plan").isNull()).isTrue();
        assertThat(st.path("agent").isNull()).isTrue();
        assertThat(st.path("language_options").size()).isGreaterThanOrEqualTo(5);
        assertThat(links(customer)).isZero();
    }

    @Test
    void exploreCustomersAreHandledByTheOnDutyAgentWithoutADedicatedLink() {
        String customer = api.loginOnly(ApiClient.randomPhone(), "CUSTOMER");
        assertThat(api.post("/api/v1/customer/onboarding/explore", customer, Map.of()).data().path("plan").asText()).isEqualTo("EXPLORE");
        String requestId = createSpecialRequest(customer);
        var row = jdbc.sql("SELECT agent_id IS NOT NULL AS has_agent, agent_source FROM service_requests WHERE id = ?::uuid").param(requestId).query().singleRow();
        assertThat(row.get("has_agent")).isEqualTo(true);
        assertThat(row.get("agent_source")).isEqualTo("ON_DUTY");
        assertThat(links(customer)).isZero();
        assertThat(api.get("/api/v1/customer/agent", customer).data().path("plan").asText()).isEqualTo("EXPLORE");
    }

    @Test
    void subscribingMatchesAnAgentWhoSpeaksTheCustomersLanguage() {
        String customer = api.loginOnly(ApiClient.randomPhone(), "CUSTOMER");
        api.post("/api/v1/customer/onboarding/explore", customer, Map.of());

        var bad = api.post("/api/v1/customer/agent/subscribe", customer, Map.of("languages", List.of("xx")));
        assertThat(bad.status()).isEqualTo(400);

        var sub = api.post("/api/v1/customer/agent/subscribe", customer, Map.of("full_name", "Anjali Menon", "languages", List.of("ml", "en"),
                "contact_method", "WHATSAPP", "contact_time", "EVENING"));
        assertThat(sub.status()).as(sub.body().toString()).isEqualTo(200);
        JsonNode agent = sub.data().path("agent");
        assertThat(agent.path("display_name").asText()).isEqualTo("Priya Nair");
        assertThat(agent.path("shared_languages").toString()).contains("Malayalam");
        assertThat(sub.data().path("pending").asBoolean()).isFalse();

        JsonNode st = api.get("/api/v1/customer/onboarding", customer).data();
        assertThat(st.path("plan").asText()).isEqualTo("DEDICATED");
        assertThat(st.path("onboarded").asBoolean()).isTrue();
        assertThat(st.path("full_name").asText()).isEqualTo("Anjali Menon");
        assertThat(st.path("preferences").path("contact_method").asText()).isEqualTo("WHATSAPP");

        // Choosing "explore" later never downgrades a subscription.
        assertThat(api.post("/api/v1/customer/onboarding/explore", customer, Map.of()).data().path("plan").asText()).isEqualTo("DEDICATED");

        // Requests now go to the dedicated agent.
        String requestId = createSpecialRequest(customer);
        var row = jdbc.sql("""
                SELECT a.display_name, r.agent_source FROM service_requests r JOIN agents a ON a.id = r.agent_id WHERE r.id = ?::uuid""")
                .param(requestId).query().singleRow();
        assertThat(row.get("display_name")).isEqualTo("Priya Nair");
        assertThat(row.get("agent_source")).isEqualTo("DEDICATED");

        // Subscribing again only updates preferences; the agent stays the same.
        var again = api.post("/api/v1/customer/agent/subscribe", customer, Map.of("languages", List.of("te")));
        assertThat(again.data().path("already_had_agent").asBoolean()).isTrue();
        assertThat(again.data().path("agent").path("display_name").asText()).isEqualTo("Priya Nair");
    }
}
