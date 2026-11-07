package com.buzzer.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;






/** Small HTTP helper for API-level tests (snake_case JSON, envelope {data} / {error}). */
public class ApiClient {
    public record Response(int status, JsonNode body) {
        public JsonNode data() {
            return body.path("data");
        }

        public String errorCode() {
            return body.path("error").path("code").asText(null);
        }
    }

    private final MockMvc mvc;
    private final ObjectMapper mapper;

    public ApiClient(MockMvc mvc, ObjectMapper mapper) {
        this.mvc = mvc;
        this.mapper = mapper;
    }

    public static String randomPhone() {
        return "7" + String.format("%09d", ThreadLocalRandom.current().nextLong(0, 1_000_000_000L));
    }

    /** OTP login. Works because the dev profile returns the OTP in the response. */
    public String login(String phone, String app) {
        Response otp = call("POST", "/api/v1/auth/otp/request", null, Map.of("phone", phone, "app", app), null);
        if (otp.status() != 200) throw new IllegalStateException("OTP request failed: " + otp.body());
        String code = otp.data().path("dev_code").asText(null);
        if (code == null) throw new IllegalStateException("No dev OTP for " + phone + " (" + app + "): " + otp.body());
        Response v = call("POST", "/api/v1/auth/otp/verify", null, Map.of("phone", phone, "code", code, "app", app, "full_name", "Test " + phone), null);
        if (v.status() != 200) throw new IllegalStateException("Login failed: " + v.body());
        return v.data().path("tokens").path("access_token").asText();
    }

    public Response get(String url, String token) {
        return call("GET", url, token, null, null);
    }

    public Response post(String url, String token, Object body) {
        return call("POST", url, token, body, null);
    }

    public Response post(String url, String token, Object body, String idempotencyKey) {
        return call("POST", url, token, body, idempotencyKey);
    }

    public Response put(String url, String token, Object body) {
        return call("PUT", url, token, body, null);
    }

    public Response call(String method, String url, String token, Object body, String idempotencyKey) {
        try {
            MockHttpServletRequestBuilder b = switch (method) {
                case "GET" -> MockMvcRequestBuilders.get(url);
                case "POST" -> MockMvcRequestBuilders.post(url);
                case "PUT" -> MockMvcRequestBuilders.put(url);
                case "DELETE" -> MockMvcRequestBuilders.delete(url);
                default -> throw new IllegalArgumentException(method);
            };
            if (token != null) b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
            if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
            MvcResult r = mvc.perform(b).andReturn();
            String content = r.getResponse().getContentAsString();
            return new Response(r.getResponse().getStatus(), content.isBlank() ? mapper.createObjectNode() : mapper.readTree(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String key() {
        return UUID.randomUUID().toString();
    }
}
