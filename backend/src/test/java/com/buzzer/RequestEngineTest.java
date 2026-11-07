package com.buzzer;

import com.buzzer.support.ApiClient;
import com.buzzer.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Stage 4: shared request identity with request types, status groups, private attachments and parent/child requests. */
@AutoConfigureMockMvc
class RequestEngineTest extends IntegrationTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] PDF = "%PDF-1.4\n%test document\n".getBytes();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
        jdbc.sql("UPDATE platform_verticals SET enabled = TRUE").update();
        jdbc.sql("UPDATE partners SET availability = 'AVAILABLE'").update();
    }

    private String address(String token) {
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        return api.post("/api/v1/customer/addresses", token, Map.of("label", "HOME", "line1", "12 Lake Road, Miyapur", "city_id", city,
                "pincode", "500049")).data().path("id").asText();
    }

    private String item(String category, String code) {
        return jdbc.sql("""
                SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                WHERE c.code = ? AND i.code = ?""").param(category).param(code).query(String.class).single();
    }

    private JsonNode upload(String url, String token, byte[] bytes, String name, String type, String visibility) throws Exception {
        MvcResult r = mvc.perform(multipart(url).file(new MockMultipartFile("file", name, type, bytes)).param("visibility", visibility)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private List<String> field(JsonNode list, String f) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path(f).asText()));
        return out;
    }

    @Test
    void everyRequestGetsATypeVerticalAndLifecycleAndCanBeFilteredByTypeAndGroup() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String addr = address(customer);
        // Product RFQ (marketplace), service booking, task in own words, personal assistance.
        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", item("HARDWARE_SUPPLIES", "LED_BULB_9W"), "quantity", 2));
        String rfq = api.post("/api/v1/customer/cart/checkout", customer, Map.of("mode", "DIRECT_QUOTE", "address_id", addr), ApiClient.key())
                .data().get(0).path("id").asText();
        String service = api.post("/api/v1/customer/requests", customer, Map.of("service_item_id", item("HOME_REPAIRS", "TAP_REPAIR"), "address_id", addr,
                "answers", Map.of("problem_area", "Kitchen", "leak_severity", "Slow drip", "details", "Kitchen tap drips all the time"))).data().path("id").asText();
        String task = api.post("/api/v1/customer/agent-request", customer, Map.of("message", "Pick up my passport photos from the studio", "kind", "TASK"),
                ApiClient.key()).data().path("id").asText();
        String help = api.post("/api/v1/customer/agent-request", customer, Map.of("message", "Plan a small birthday dinner for 6 people"), ApiClient.key())
                .data().path("id").asText();

        var row = jdbc.sql("SELECT request_type, vertical, lifecycle FROM service_requests WHERE id = ?::uuid");
        assertThat(row.param(rfq).query().singleRow()).containsEntry("request_type", "PRODUCT_RFQ").containsEntry("vertical", "MARKETPLACE")
                .containsEntry("lifecycle", "REQUEST_V1");
        assertThat(jdbc.sql("SELECT request_type FROM service_requests WHERE id = ?::uuid").param(service).query(String.class).single()).isEqualTo("SERVICE_BOOKING");
        assertThat(jdbc.sql("SELECT request_type FROM service_requests WHERE id = ?::uuid").param(task).query(String.class).single()).isEqualTo("TASK_REQUEST");
        assertThat(jdbc.sql("SELECT request_type FROM service_requests WHERE id = ?::uuid").param(help).query(String.class).single()).isEqualTo("PERSONAL_ASSISTANCE");

        JsonNode rfqs = api.get("/api/v1/customer/requests?type=PRODUCT_RFQ", customer).data();
        assertThat(field(rfqs, "id")).containsExactly(rfq);
        assertThat(rfqs.get(0).path("status_group").asText()).isEqualTo("OPEN");
        assertThat(api.get("/api/v1/customer/requests?group=OPEN", customer).data().size()).isEqualTo(4);
        assertThat(api.get("/api/v1/customer/requests?type=NOPE", customer).status()).isEqualTo(400);

        // The "requests" view exposes the shared identity for reporting.
        assertThat(jdbc.sql("SELECT count(*) FROM requests WHERE customer_user_id = (SELECT customer_user_id FROM service_requests WHERE id = ?::uuid)")
                .param(rfq).query(Integer.class).single()).isEqualTo(4);
        assertThat(api.get("/api/v1/customer/requests/" + help, customer).data().path("request_type_label").asText()).isEqualTo("Personal assistance");
    }

    @Test
    void attachmentsArePrivateSignedAndSharedOnlyWithInvitedPartnersWhenAllowed() throws Exception {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String other = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String addr = address(customer);
        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", item("HARDWARE_SUPPLIES", "MCB_16A"), "quantity", 1));
        String rfq = api.post("/api/v1/customer/cart/checkout", customer, Map.of("mode", "DIRECT_QUOTE", "address_id", addr), ApiClient.key())
                .data().get(0).path("id").asText();
        String url = "/api/v1/customer/requests/" + rfq + "/attachments";

        JsonNode photo = upload(url, customer, PNG, "board photo.png", "image/png", "PARTNERS");
        assertThat(photo.path("data").path("content_type").asText()).isEqualTo("image/png");
        JsonNode privateDoc = upload(url, customer, PDF, "../../etc/passwd.pdf", "application/pdf", "PRIVATE");
        assertThat(privateDoc.path("data").path("file_name").asText()).isEqualTo("etcpasswd.pdf");
        // Content is checked, not the name or header: a text file pretending to be a PNG is refused.
        assertThat(upload(url, customer, "hello".getBytes(), "fake.png", "image/png", "PRIVATE").path("error").path("code").asText()).isNotBlank();
        assertThat(upload(url, customer, new byte[AttachmentLimits.TOO_BIG], "big.png", "image/png", "PRIVATE").path("error").path("code").asText()).isNotBlank();
        // Another customer can neither list nor upload.
        assertThat(api.get(url, other).status()).isEqualTo(404);
        assertThat(upload(url, other, PNG, "x.png", "image/png", "PRIVATE").path("error").path("code").asText()).isNotBlank();

        JsonNode mine = api.get(url, customer).data();
        assertThat(mine.size()).isEqualTo(2);
        String signed = mine.get(0).path("url").asText();
        MvcResult file = mvc.perform(get(signed)).andReturn();
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(Arrays.equals(file.getResponse().getContentAsByteArray(), PNG)).isTrue();
        // Tampered or expired links look like "not found".
        assertThat(mvc.perform(get(signed.replaceAll("sig=.", "sig=X"))).andReturn().getResponse().getStatus()).isEqualTo(404);
        String expired = signed.replaceAll("exp=\\d+", "exp=1000");
        assertThat(mvc.perform(get(expired)).andReturn().getResponse().getStatus()).isEqualTo(404);

        // Invited partner sees only the shared photo; the private PDF stays hidden.
        String shop = api.login("9000000026", "PARTNER");
        String inv = null;
        for (JsonNode l : api.get("/api/v1/partner/leads", shop).data()) if (l.path("request_id").asText().equals(rfq)) inv = l.path("invitation_id").asText();
        JsonNode lead = api.get("/api/v1/partner/leads/" + inv, shop).data();
        assertThat(field(lead.path("attachments"), "file_name")).containsExactly("board photo.png");
        // A partner who was not invited cannot open the lead.
        String plumber = api.login("9000000021", "PARTNER");
        assertThat(api.get("/api/v1/partner/leads/" + inv, plumber).status()).isEqualTo(404);

        // The customer can remove their own file; the timeline records uploads.
        String pdfId = privateDoc.path("data").path("id").asText();
        assertThat(api.call("DELETE", url + "/" + pdfId, customer, null, null).status()).isEqualTo(200);
        assertThat(api.get(url, customer).data().size()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM request_status_history WHERE request_id = ?::uuid AND reason LIKE 'Attachment added%'")
                .param(rfq).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void agentsCreateChildRequestsForAnAssistanceCase() {
        String phone = ApiClient.randomPhone();
        String customer = api.login(phone, "CUSTOMER");
        String addr = address(customer);
        String caseId = api.post("/api/v1/customer/agent-request", customer, Map.of("message", "Help me set up my new flat: bulbs, a plumber and cleaning"),
                ApiClient.key()).data().path("id").asText();
        String agentPhone = jdbc.sql("""
                SELECT substring(u.phone from 4) FROM customer_agent_links l JOIN agents a ON a.id = l.agent_id JOIN users u ON u.id = a.user_id
                JOIN users c ON c.id = l.customer_user_id WHERE c.phone = ? AND l.link_role = 'PRIMARY' AND l.active""").param("+91" + phone).query(String.class).single();
        String agent = api.login(agentPhone, "AGENT");
        String customerId = jdbc.sql("SELECT id::text FROM users WHERE phone = ?").param("+91" + phone).query(String.class).single();

        var child = api.post("/api/v1/agent/requests", agent, Map.of("customer_user_id", customerId, "service_item_id", item("HOME_REPAIRS", "TAP_REPAIR"),
                "address_id", addr, "answers", Map.of("problem_area", "Bathroom", "leak_severity", "Slow drip", "details", "New flat - check all bathroom taps"),
                "consent_method", "PHONE_CALL", "parent_request_id", caseId), ApiClient.key());
        assertThat(child.status()).as(child.body().toString()).isEqualTo(200);
        String childId = child.data().path("id").asText();

        JsonNode parent = api.get("/api/v1/customer/requests/" + caseId, customer).data();
        assertThat(field(parent.path("children"), "id")).containsExactly(childId);
        assertThat(api.get("/api/v1/customer/requests/" + childId, customer).data().path("parent").path("id").asText()).isEqualTo(caseId);

        // A parent belonging to another customer is refused.
        String strangerCase = api.post("/api/v1/customer/agent-request", api.login(ApiClient.randomPhone(), "CUSTOMER"),
                Map.of("message", "Someone else's request for help"), ApiClient.key()).data().path("id").asText();
        var bad = api.post("/api/v1/agent/requests", agent, Map.of("customer_user_id", customerId, "service_item_id", item("HOME_REPAIRS", "TAP_REPAIR"),
                "address_id", addr, "answers", Map.of("problem_area", "Kitchen", "leak_severity", "Slow drip", "details", "Another tap that needs fixing"),
                "consent_method", "PHONE_CALL", "parent_request_id", strangerCase), ApiClient.key());
        assertThat(bad.status()).isIn(400, 403, 404);
    }

    /** Sizes used by the tests. */
    static final class AttachmentLimits {
        static final int TOO_BIG = 5 * 1024 * 1024 + 1;
    }
}
