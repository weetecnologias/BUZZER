package com.buzzer;

import com.buzzer.support.ApiClient;
import com.buzzer.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
class AuthAndAccessTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
    }

    @Test
    void customerRegistrationViaOtpCreatesProfileAndDedicatedAgents() {
        String phone = ApiClient.randomPhone();
        String token = api.login(phone, "CUSTOMER");
        var agent = api.get("/api/v1/customer/agent", token);
        assertThat(agent.status()).isEqualTo(200);
        assertThat(agent.data().path("primary").path("display_name").asText()).isNotBlank();
        assertThat(agent.data().path("backup").path("display_name").asText()).isNotBlank();
        assertThat(agent.data().path("primary").path("agent_id").asText())
                .isNotEqualTo(agent.data().path("backup").path("agent_id").asText());
        // Managers are only used when no regular agent is available.
        assertThat(agent.data().path("primary").path("display_name").asText()).isNotEqualTo("Suresh Reddy");
    }

    @Test
    void wrongOtpIsRejectedAndAttemptsAreLimited() {
        String phone = ApiClient.randomPhone();
        api.post("/api/v1/auth/otp/request", null, Map.of("phone", phone, "app", "CUSTOMER"));
        for (int i = 0; i < 5; i++) {
            var r = api.post("/api/v1/auth/otp/verify", null, Map.of("phone", phone, "code", "000000", "app", "CUSTOMER"));
            // (a random correct code of 000000 is astronomically unlikely; still assert on the code family)
            assertThat(r.errorCode()).isIn("OTP_INVALID", null);
        }
        var locked = api.post("/api/v1/auth/otp/verify", null, Map.of("phone", phone, "code", "000000", "app", "CUSTOMER"));
        assertThat(locked.errorCode()).isEqualTo("OTP_TOO_MANY_ATTEMPTS");
    }

    @Test
    void otpIsSingleUse() {
        String phone = ApiClient.randomPhone();
        var otp = api.post("/api/v1/auth/otp/request", null, Map.of("phone", phone, "app", "CUSTOMER"));
        String code = otp.data().path("dev_code").asText();
        assertThat(api.post("/api/v1/auth/otp/verify", null, Map.of("phone", phone, "code", code, "app", "CUSTOMER")).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/auth/otp/verify", null, Map.of("phone", phone, "code", code, "app", "CUSTOMER")).errorCode()).isEqualTo("OTP_INVALID");
    }

    @Test
    void staffAppsDoNotLetUnknownNumbersIn() {
        String phone = ApiClient.randomPhone();
        var otp = api.post("/api/v1/auth/otp/request", null, Map.of("phone", phone, "app", "ADMIN"));
        assertThat(otp.status()).isEqualTo(200);                    // same response shape: no account enumeration
        assertThat(otp.data().path("dev_code").isNull()).isTrue();   // but no code is issued/sent
        var verify = api.post("/api/v1/auth/otp/verify", null, Map.of("phone", phone, "code", "123456", "app", "ADMIN"));
        assertThat(verify.status()).isIn(400, 403);
    }

    @Test
    void roleBasedAccessIsEnforced() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        assertThat(api.get("/api/v1/admin/dashboard", customer).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/agent/requests", customer).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/partner/leads", customer).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/customer/profile", null).status()).isEqualTo(401);

        String agent = api.login("9000000011", "AGENT");
        assertThat(api.get("/api/v1/agent/dashboard", agent).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/admin/partners", agent).status()).isEqualTo(403);

        String admin = api.login("9000000001", "ADMIN");
        assertThat(api.get("/api/v1/admin/dashboard", admin).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/admin/system-health", admin).data().path("components").size()).isGreaterThan(10);
    }

    @Test
    void refreshTokensRotateAndOldOnesAreRevoked() {
        String phone = ApiClient.randomPhone();
        var otp = api.post("/api/v1/auth/otp/request", null, Map.of("phone", phone, "app", "CUSTOMER"));
        var v = api.post("/api/v1/auth/otp/verify", null, Map.of("phone", phone, "code", otp.data().path("dev_code").asText(), "app", "CUSTOMER"));
        String refresh = v.data().path("tokens").path("refresh_token").asText();
        var r1 = api.post("/api/v1/auth/refresh", null, Map.of("refresh_token", refresh));
        assertThat(r1.status()).isEqualTo(200);
        var reuse = api.post("/api/v1/auth/refresh", null, Map.of("refresh_token", refresh));
        assertThat(reuse.errorCode()).isEqualTo("SESSION_INVALID");
    }

    @Test
    void agentCannotOpenCustomersOutsidePortfolio() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String customerId = api.get("/api/v1/customer/profile", customer).data().path("id").asText();
        String primaryAgentName = api.get("/api/v1/customer/agent", customer).data().path("primary").path("display_name").asText();
        String backupAgentName = api.get("/api/v1/customer/agent", customer).data().path("backup").path("display_name").asText();
        // Find an agent that is neither primary, backup nor their manager: create one.
        String outsiderPhone = ApiClient.randomPhone();
        String admin = api.login("9000000001", "ADMIN");
        String cityId = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        var created = api.post("/api/v1/admin/agents", admin, Map.of("phone", outsiderPhone, "display_name", "Outsider Agent", "city_id", cityId,
                "max_customers", 1));
        assertThat(created.status()).isEqualTo(200);
        String outsider = api.login(outsiderPhone, "AGENT");
        assertThat(primaryAgentName).isNotEqualTo("Outsider Agent");
        assertThat(backupAgentName).isNotEqualTo("Outsider Agent");
        assertThat(api.get("/api/v1/agent/customers/" + customerId, outsider).status()).isEqualTo(403);
    }
}
