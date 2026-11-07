package com.buzzer;

import com.buzzer.support.ApiClient;
import com.buzzer.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance scenarios 1-6: register -> plumbing request -> agent outreach -> 3 quotations -> compare ->
 * accept + pay -> exactly one assignment -> confirm/arrive/start OTP/complete OTP -> ledger + commission
 * -> payout -> settled -> review.
 */
@AutoConfigureMockMvc
class EndToEndServiceFlowTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    private String one(String sql, Object... params) {
        var s = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) s = s.param(i + 1, params[i]);
        return s.query(String.class).single();
    }

    @Test
    void plumbingLeakageRequestFromRegistrationToSettlement() {
        ApiClient api = new ApiClient(mvc, mapper);

        // --- Scenario 1: customer registers, gets an agent, submits a plumbing leakage request ------------------
        String customerPhone = ApiClient.randomPhone();
        String customer = api.login(customerPhone, "CUSTOMER");
        String city = one("SELECT id::text FROM cities WHERE code = 'HYD'");
        String kondapur = one("SELECT id::text FROM localities WHERE name = 'Kondapur'");
        String address = api.post("/api/v1/customer/addresses", customer, Map.of("label", "HOME", "line1", "Flat 402, Lake View Apartments",
                "landmark", "Near Botanical Garden", "city_id", city, "locality_id", kondapur, "pincode", "500084")).data().path("id").asText();
        String leakage = one("SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id WHERE s.code='PLUMBING' AND i.code='PIPE_LEAKAGE'");
        String idem = ApiClient.key();
        Map<String, Object> body = Map.of("service_item_id", leakage, "address_id", address, "answers", Map.of(
                "problem_area", "Kitchen", "leak_severity", "Steady leak", "details", "Water leaking from the pipe under the kitchen sink"));
        var created = api.post("/api/v1/customer/requests", customer, body, idem);
        assertThat(created.status()).isEqualTo(200);
        String requestId = created.data().path("id").asText();
        assertThat(created.data().path("request_number").asText()).matches("BZ-REQ-\\d{4}-\\d{8}");
        // Duplicate submit (same Idempotency-Key) returns the same request instead of creating a second one.
        var dup = api.post("/api/v1/customer/requests", customer, body, idem);
        assertThat(dup.data().path("id").asText()).isEqualTo(requestId);
        assertThat(dup.data().path("duplicate").asBoolean()).isTrue();

        // --- Scenario 2: the customer's agent sees it, finds eligible plumbers only, invites them ---------------
        String agentPhone = one("""
                SELECT substring(u.phone from 4) FROM customer_agent_links l JOIN agents a ON a.id = l.agent_id JOIN users u ON u.id = a.user_id
                JOIN users c ON c.id = l.customer_user_id WHERE c.phone = ? AND l.link_role = 'PRIMARY' AND l.active""", "+91" + customerPhone);
        String agent = api.login(agentPhone, "AGENT");
        JsonNode inbox = api.get("/api/v1/agent/requests", agent).data();
        boolean inInbox = false;
        for (JsonNode r : inbox) inInbox |= r.path("id").asText().equals(requestId);
        assertThat(inInbox).isTrue();
        assertThat(api.post("/api/v1/agent/requests/" + requestId + "/acknowledge", agent, Map.of()).data().path("status").asText()).isEqualTo("AGENT_REVIEW");

        JsonNode eligible = api.get("/api/v1/agent/requests/" + requestId + "/eligible-partners", agent).data();
        List<String> names = new ArrayList<>();
        List<String> partnerIds = new ArrayList<>();
        eligible.forEach(p -> {
            names.add(p.path("display_name").asText());
            partnerIds.add(p.path("id").asText());
        });
        assertThat(names).contains("Ramesh Plumbing Works", "Quick Fix Plumbers", "Aqua Care Services");
        assertThat(names).doesNotContain("Bright Spark Electricals", "New Partner Pending");
        String electrician = one("SELECT p.id::text FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = '+919000000024'");
        assertThat(api.post("/api/v1/agent/requests/" + requestId + "/invitations", agent, Map.of("partner_ids", List.of(electrician))).errorCode())
                .isEqualTo("PARTNER_NOT_ELIGIBLE");
        List<String> plumbers = new ArrayList<>();
        for (String phone : List.of("+919000000021", "+919000000022", "+919000000023")) {
            plumbers.add(one("SELECT p.id::text FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = ?", phone));
        }
        assertThat(partnerIds).containsAll(plumbers);
        var invited = api.post("/api/v1/agent/requests/" + requestId + "/invitations", agent, Map.of("partner_ids", plumbers));
        assertThat(invited.status()).isEqualTo(200);

        // --- Scenario 3: three partners quote; agent compares and shares --------------------------------------
        String[] partnerPhones = {"9000000021", "9000000022", "9000000023"};
        int[] labour = {450, 380, 520};
        List<String> quotationIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String partner = api.login(partnerPhones[i], "PARTNER");
            JsonNode leads = api.get("/api/v1/partner/leads?status=INVITED", partner).data();
            String invitationId = null;
            for (JsonNode l : leads) if (l.path("request_id").asText().equals(requestId)) invitationId = l.path("invitation_id").asText();
            assertThat(invitationId).as("partner %s sees the lead", partnerPhones[i]).isNotNull();
            var q = api.post("/api/v1/partner/leads/" + invitationId + "/quote", partner, Map.of(
                    "scope_of_work", "Replace damaged section of kitchen sink pipe and reseal joints",
                    "labour_amount", labour[i], "materials_amount", 200, "visit_fee", 100, "tax_amount", 0, "discount_amount", 0,
                    "warranty", "30 days on workmanship", "pay_on_delivery_allowed", true, "total_amount", 1));
            assertThat(q.status()).isEqualTo(200);
            // Server computed the total; the bogus client "total_amount" was ignored.
            assertThat(q.data().path("total_amount").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(labour[i] + 300));
            quotationIds.add(q.data().path("id").asText());
        }
        // A partner cannot see another partner's lead.
        String partnerB = api.login("9000000022", "PARTNER");
        String partnerAInvitation = one("SELECT i.id::text FROM partner_invitations i JOIN partners p ON p.id = i.partner_id JOIN users u ON u.id = p.user_id WHERE u.phone='+919000000021' AND i.request_id = ?::uuid", requestId);
        assertThat(api.post("/api/v1/partner/leads/" + partnerAInvitation + "/quote", partnerB, Map.of("scope_of_work", "Sneaky", "labour_amount", 1)).status())
                .isEqualTo(404);

        JsonNode agentView = api.get("/api/v1/agent/requests/" + requestId, agent).data();
        assertThat(agentView.path("quotations").size()).isEqualTo(3);
        assertThat(agentView.path("quotations").get(0).path("total_amount").decimalValue()).isEqualByComparingTo("680"); // sorted cheapest first
        assertThat(api.post("/api/v1/agent/requests/" + requestId + "/share-quotations", agent,
                Map.of("quotation_ids", quotationIds, "note", "Quick Fix is cheapest with good ratings")).status()).isEqualTo(200);

        JsonNode customerView = api.get("/api/v1/customer/requests/" + requestId, customer).data();
        assertThat(customerView.path("status").asText()).isEqualTo("CUSTOMER_APPROVAL");
        assertThat(customerView.path("quotations").size()).isEqualTo(3);

        // --- Scenario 4: customer approves one and pays; exactly one assignment ---------------------------------
        String chosen = quotationIds.get(1); // Quick Fix: 680
        String payKey = ApiClient.key();
        var accept = api.post("/api/v1/customer/requests/" + requestId + "/quotations/" + chosen + "/accept", customer,
                Map.of("payment_method", "ONLINE"), payKey);
        assertThat(accept.status()).isEqualTo(200);
        JsonNode payment = accept.data().path("payment");
        assertThat(payment.path("status").asText()).isEqualTo("PENDING");
        assertThat(payment.path("test_mode").asBoolean()).isTrue();
        // Retried accept with the same key does not create a second payment.
        var acceptAgain = api.post("/api/v1/customer/requests/" + requestId + "/quotations/" + chosen + "/accept", customer,
                Map.of("payment_method", "ONLINE"), payKey);
        assertThat(acceptAgain.data().path("duplicate").asBoolean()).isTrue();
        assertThat(api.get("/api/v1/customer/requests/" + requestId, customer).data().path("status").asText()).isEqualTo("PAYMENT_PENDING");

        String paymentId = payment.path("id").asText();
        var sim = api.post("/api/v1/customer/payments/" + paymentId + "/simulate", customer, Map.of("outcome", "SUCCESS"));
        assertThat(sim.data().path("result").asText()).isEqualTo("OK_SUCCEEDED");
        Integer live = jdbc.sql("SELECT count(*) FROM assignments WHERE request_id = ?::uuid AND status IN ('ACTIVE','CONFIRMED','IN_PROGRESS')")
                .param(requestId).query(Integer.class).single();
        assertThat(live).isEqualTo(1);
        assertThat(one("SELECT status FROM service_requests WHERE id = ?::uuid", requestId)).isEqualTo("PARTNER_ASSIGNED");
        Integer closedInvites = jdbc.sql("SELECT count(*) FROM partner_invitations WHERE request_id = ?::uuid AND status = 'CLOSED'")
                .param(requestId).query(Integer.class).single();
        assertThat(closedInvites).isEqualTo(2);

        // (Duplicate webhook delivery and signature checks are covered in PaymentResilienceTest.)

        // --- Scenario 5: partner confirms, arrives, start OTP, completes with completion OTP --------------------
        String partner = api.login("9000000022", "PARTNER");
        String assignmentId = one("SELECT id::text FROM assignments WHERE request_id = ?::uuid AND status = 'ACTIVE'", requestId);
        // Another partner cannot act on this assignment.
        assertThat(api.post("/api/v1/partner/assignments/" + assignmentId + "/confirm", api.login("9000000021", "PARTNER"), Map.of()).status()).isEqualTo(404);
        var confirm = api.post("/api/v1/partner/assignments/" + assignmentId + "/confirm", partner, Map.of());
        assertThat(confirm.data().path("booking_number").asText()).startsWith("BZ-BKG-");
        assertThat(api.post("/api/v1/partner/assignments/" + assignmentId + "/arrived", partner, Map.of()).status()).isEqualTo(200);

        String startOtp = api.get("/api/v1/customer/requests/" + requestId + "/service-otp?purpose=START", customer).data().path("otp").asText();
        assertThat(api.post("/api/v1/partner/assignments/" + assignmentId + "/start", partner, Map.of("otp", "0000".equals(startOtp) ? "1111" : "0000"))
                .errorCode()).isEqualTo("OTP_INVALID");
        assertThat(api.post("/api/v1/partner/assignments/" + assignmentId + "/start", partner, Map.of("otp", startOtp)).status()).isEqualTo(200);
        // Single use: replaying the same start OTP fails (state already moved on).
        assertThat(api.post("/api/v1/partner/assignments/" + assignmentId + "/start", partner, Map.of("otp", startOtp)).status()).isEqualTo(409);

        assertThat(api.post("/api/v1/partner/assignments/" + assignmentId + "/request-completion", partner, Map.of()).status()).isEqualTo(200);
        String doneOtp = api.get("/api/v1/customer/requests/" + requestId + "/service-otp?purpose=COMPLETE", customer).data().path("otp").asText();
        assertThat(api.post("/api/v1/partner/assignments/" + assignmentId + "/complete", partner, Map.of("otp", doneOtp)).status()).isEqualTo(200);

        // --- Scenario 6: ledger, commission (Plumbing 5%), payout, settlement, feedback --------------------------
        assertThat(one("SELECT status FROM service_requests WHERE id = ?::uuid", requestId)).isEqualTo("SETTLEMENT_PENDING");
        BigDecimal commission = jdbc.sql("SELECT amount FROM ledger_entries WHERE request_id = ?::uuid AND account = 'PLATFORM_COMMISSION'")
                .param(requestId).query(BigDecimal.class).single();
        BigDecimal earning = jdbc.sql("SELECT amount FROM ledger_entries WHERE request_id = ?::uuid AND account = 'PARTNER_EARNING'")
                .param(requestId).query(BigDecimal.class).single();
        assertThat(commission).isEqualByComparingTo("34.00");   // 5% of 680
        assertThat(earning).isEqualByComparingTo("646.00");
        assertThat(jdbc.sql("SELECT count(*) FROM invoices WHERE request_id = ?::uuid").param(requestId).query(Integer.class).single()).isEqualTo(2);

        JsonNode earnings = api.get("/api/v1/partner/earnings", partner).data();
        assertThat(earnings.path("payouts").size()).isGreaterThan(0);

        String finance = api.login("9000000001", "ADMIN");
        String payoutId = one("SELECT id::text FROM partner_payouts WHERE request_id = ?::uuid", requestId);
        assertThat(api.post("/api/v1/admin/finance/payouts/" + payoutId + "/paid", finance, Map.of("bank_reference", "UTR123456789")).status()).isEqualTo(200);
        assertThat(one("SELECT status FROM service_requests WHERE id = ?::uuid", requestId)).isEqualTo("SETTLED");

        assertThat(api.post("/api/v1/customer/requests/" + requestId + "/review", customer, Map.of("rating", 5, "comment", "Quick and clean work")).status())
                .isEqualTo(200);
        assertThat(one("SELECT rating_count::text FROM partners WHERE id = ?::uuid", plumbers.get(1))).isNotEqualTo("0");

        // Full audit trail of the lifecycle.
        Integer transitions = jdbc.sql("SELECT count(*) FROM request_status_history WHERE request_id = ?::uuid").param(requestId).query(Integer.class).single();
        assertThat(transitions).isGreaterThanOrEqualTo(14);
    }
}
