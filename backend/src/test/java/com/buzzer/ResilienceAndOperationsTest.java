package com.buzzer;

import com.buzzer.assignment.AssignmentService;
import com.buzzer.common.BusinessException;
import com.buzzer.finance.CommissionService;
import com.buzzer.notification.OutboxDispatcher;
import com.buzzer.ops.EscalationService;
import com.buzzer.payment.MockPaymentProvider;
import com.buzzer.payment.PaymentService;
import com.buzzer.support.ApiClient;
import com.buzzer.support.FlowHelper;
import com.buzzer.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scenarios 7, 8, 11 plus webhook security, idempotency and commission precedence. */
@AutoConfigureMockMvc
class ResilienceAndOperationsTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired PaymentService payments;
    @Autowired MockPaymentProvider mockProvider;
    @Autowired EscalationService escalations;
    @Autowired AssignmentService assignments;
    @Autowired OutboxDispatcher outbox;
    @Autowired CommissionService commissions;

    @Test
    void paymentFailureKeepsRequestSafeAndRetryWorks() {
        ApiClient api = new ApiClient(mvc, mapper);
        FlowHelper.Prepared p = new FlowHelper(api, jdbc).toCustomerApproval(2, false);
        var accept = api.post("/api/v1/customer/requests/" + p.requestId() + "/quotations/" + p.quotationIds().get(0) + "/accept",
                p.customerToken(), Map.of("payment_method", "ONLINE"), ApiClient.key());
        String paymentId = accept.data().path("payment").path("id").asText();
        assertThat(api.post("/api/v1/customer/payments/" + paymentId + "/simulate", p.customerToken(), Map.of("outcome", "FAILURE"))
                .data().path("result").asText()).isEqualTo("OK_FAILED");
        // Request is intact and still awaiting payment; nothing was assigned.
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(p.requestId()).query(String.class).single())
                .isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.sql("SELECT count(*) FROM assignments WHERE request_id = ?::uuid").param(p.requestId()).query(Integer.class).single()).isZero();

        var retry = api.post("/api/v1/customer/requests/" + p.requestId() + "/payments/retry", p.customerToken(), Map.of(), ApiClient.key());
        assertThat(retry.data().path("status").asText()).isEqualTo("PENDING");
        api.post("/api/v1/customer/payments/" + retry.data().path("id").asText() + "/simulate", p.customerToken(), Map.of("outcome", "SUCCESS"));
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(p.requestId()).query(String.class).single())
                .isEqualTo("PARTNER_ASSIGNED");
    }

    @Test
    void webhookSignatureIsVerifiedAndDuplicatesAreIgnored() {
        ApiClient api = new ApiClient(mvc, mapper);
        FlowHelper.Prepared p = new FlowHelper(api, jdbc).toCustomerApproval(1, false);
        var accept = api.post("/api/v1/customer/requests/" + p.requestId() + "/quotations/" + p.quotationIds().get(0) + "/accept",
                p.customerToken(), Map.of("payment_method", "ONLINE"), ApiClient.key());
        String orderId = accept.data().path("payment").path("provider_order_id").asText();
        BigDecimal amount = accept.data().path("payment").path("amount").decimalValue();
        String body = mockProvider.buildEvent("payment.succeeded", orderId, amount, null);

        assertThatThrownBy(() -> payments.handleWebhook("MOCK", body, "forged-signature"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Invalid signature");
        assertThat(payments.handleWebhook("MOCK", body, mockProvider.sign(body))).isEqualTo("OK_SUCCEEDED");
        assertThat(payments.handleWebhook("MOCK", body, mockProvider.sign(body))).isEqualTo("DUPLICATE_IGNORED");
        // A different event id for the same success is also safe (already succeeded -> no second assignment).
        String body2 = mockProvider.buildEvent("payment.succeeded", orderId, amount, null);
        assertThat(payments.handleWebhook("MOCK", body2, mockProvider.sign(body2))).isEqualTo("OK_ALREADY_SUCCEEDED");
        assertThat(jdbc.sql("SELECT count(*) FROM assignments WHERE request_id = ?::uuid").param(p.requestId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_entries WHERE request_id = ?::uuid AND account = 'CUSTOMER_PAYMENT'")
                .param(p.requestId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void amountMismatchIsFlaggedNotAccepted() {
        ApiClient api = new ApiClient(mvc, mapper);
        FlowHelper.Prepared p = new FlowHelper(api, jdbc).toCustomerApproval(1, false);
        var accept = api.post("/api/v1/customer/requests/" + p.requestId() + "/quotations/" + p.quotationIds().get(0) + "/accept",
                p.customerToken(), Map.of("payment_method", "ONLINE"), ApiClient.key());
        String orderId = accept.data().path("payment").path("provider_order_id").asText();
        String body = mockProvider.buildEvent("payment.succeeded", orderId, new BigDecimal("1.00"), null);
        assertThat(payments.handleWebhook("MOCK", body, mockProvider.sign(body))).isEqualTo("AMOUNT_MISMATCH");
        assertThat(jdbc.sql("SELECT count(*) FROM ops_exceptions WHERE exception_type = 'PAYMENT_RECONCILIATION_MISMATCH' AND ref_id = ?::uuid")
                .param(accept.data().path("payment").path("id").asText()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void payOnDeliveryIsRefusedWhenNotPermitted() {
        ApiClient api = new ApiClient(mvc, mapper);
        FlowHelper.Prepared p = new FlowHelper(api, jdbc).toCustomerApproval(1, false);
        var r = api.post("/api/v1/customer/requests/" + p.requestId() + "/quotations/" + p.quotationIds().get(0) + "/accept",
                p.customerToken(), Map.of("payment_method", "PAY_ON_DELIVERY"), ApiClient.key());
        assertThat(r.errorCode()).isEqualTo("PAY_ON_DELIVERY_NOT_ALLOWED");
    }

    @Test
    void slaBreachEscalatesToBackupAgent() {
        ApiClient api = new ApiClient(mvc, mapper);
        String phone = ApiClient.randomPhone();
        String customer = api.login(phone, "CUSTOMER");
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        String address = api.post("/api/v1/customer/addresses", customer, Map.of("label", "WORK", "line1", "Office 5, Cyber Towers",
                "city_id", city, "pincode", "500081")).data().path("id").asText();
        String special = jdbc.sql("SELECT id::text FROM categories WHERE code = 'SPECIAL_REQUESTS'").query(String.class).single();
        String requestId = api.post("/api/v1/customer/requests", customer, Map.of("category_id", special, "address_id", address,
                "answers", Map.of("details", "Need someone to collect a parcel from the courier office", "urgency", "Today")))
                .data().path("id").asText();
        jdbc.sql("UPDATE service_requests SET sla_due_at = now() - interval '5 minutes' WHERE id = ?::uuid").param(requestId).update();

        escalations.escalateBreached();

        String backup = jdbc.sql("""
                SELECT l.agent_id::text FROM customer_agent_links l JOIN users u ON u.id = l.customer_user_id
                WHERE u.phone = ? AND l.link_role = 'BACKUP' AND l.active""").param("+91" + phone).query(String.class).single();
        var row = jdbc.sql("SELECT escalated_to_agent_id::text AS t, escalated_at IS NOT NULL AS e FROM service_requests WHERE id = ?::uuid")
                .param(requestId).query().singleRow();
        assertThat(row.get("e")).isEqualTo(true);
        assertThat(row.get("t")).isEqualTo(backup);
        assertThat(jdbc.sql("SELECT count(*) FROM ops_exceptions WHERE exception_type = 'SLA_BREACH' AND ref_id = ?::uuid")
                .param(requestId).query(Integer.class).single()).isEqualTo(1);
        // Escalated agent can open the request.
        String backupPhone = jdbc.sql("SELECT substring(u.phone from 4) FROM agents a JOIN users u ON u.id = a.user_id WHERE a.id = ?::uuid")
                .param(backup).query(String.class).single();
        assertThat(api.get("/api/v1/agent/requests/" + requestId, api.login(backupPhone, "AGENT")).status()).isEqualTo(200);
    }

    @Test
    void agentGoingOfflineEscalatesTheirUnansweredRequests() {
        ApiClient api = new ApiClient(mvc, mapper);
        // Dedicated agent pair so the test does not disturb seeded agents.
        String admin = api.login("9000000001", "ADMIN");
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        String agentPhone = ApiClient.randomPhone();
        api.post("/api/v1/admin/agents", admin, Map.of("phone", agentPhone, "display_name", "Temp Agent", "city_id", city));
        String agent = api.login(agentPhone, "AGENT");
        String customerPhone = ApiClient.randomPhone();
        var reg = api.post("/api/v1/agent/customers", agent, Map.of("phone", customerPhone, "full_name", "Phone Customer", "consent_method", "PHONE_CALL"));
        String customerId = reg.data().path("customer_user_id").asText();
        String customer = api.login(customerPhone, "CUSTOMER");
        String address = api.post("/api/v1/customer/addresses", customer, Map.of("label", "HOME", "line1", "Villa 7, Jubilee Hills",
                "city_id", city, "pincode", "500033")).data().path("id").asText();
        String special = jdbc.sql("SELECT id::text FROM categories WHERE code = 'SPECIAL_REQUESTS'").query(String.class).single();
        String requestId = api.post("/api/v1/customer/requests", customer, Map.of("category_id", special, "address_id", address,
                "answers", Map.of("details", "Please arrange a birthday cake delivery for tomorrow", "urgency", "Flexible"))).data().path("id").asText();

        var off = api.put("/api/v1/agent/availability", agent, Map.of("availability", "ON_LEAVE"));
        assertThat(off.data().path("escalated_requests").asInt()).isEqualTo(1);
        String escalatedTo = jdbc.sql("SELECT escalated_to_agent_id::text FROM service_requests WHERE id = ?::uuid").param(requestId).query(String.class).single();
        String primary = jdbc.sql("SELECT agent_id::text FROM customer_agent_links WHERE customer_user_id = ?::uuid AND link_role = 'PRIMARY' AND active")
                .param(customerId).query(String.class).single();
        assertThat(escalatedTo).isNotNull().isNotEqualTo(primary);
    }

    @Test
    void repeatedPartnerCancellationsRaiseAConfigurableAdminAlert() {
        String partnerId = jdbc.sql("SELECT p.id::text FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = '+919000000024'")
                .query(String.class).single();
        // Simulate history: 4 partner cancellations inside the window (threshold 3).
        String requestId = jdbc.sql("SELECT id::text FROM service_requests LIMIT 1").query(String.class).optional().orElse(null);
        if (requestId == null) {
            new FlowHelper(new ApiClient(mvc, mapper), jdbc).toCustomerApproval(1, true);
            requestId = jdbc.sql("SELECT id::text FROM service_requests LIMIT 1").query(String.class).single();
        }
        String quotationId = jdbc.sql("SELECT id::text FROM quotations LIMIT 1").query(String.class).single();
        for (int i = 0; i < 4; i++) {
            jdbc.sql("""
                    INSERT INTO assignments(assignment_number, request_id, partner_id, quotation_id, status, price_snapshot, cancelled_by, cancel_reason)
                    VALUES (?, ?::uuid, ?::uuid, ?::uuid, 'CANCELLED', '{}'::jsonb, 'PARTNER', 'test')""")
                    .param("TC-" + UUID.randomUUID().toString().substring(0, 18)).param(requestId).param(partnerId).param(quotationId).update();
        }
        assignments.checkCancellationThreshold(UUID.fromString(partnerId));
        assignments.checkCancellationThreshold(UUID.fromString(partnerId)); // dedupe: still one open alert
        assertThat(jdbc.sql("SELECT count(*) FROM ops_exceptions WHERE exception_type = 'PARTNER_REPEATED_CANCELLATIONS' AND ref_id = ?::uuid AND status = 'OPEN'")
                .param(partnerId).query(Integer.class).single()).isEqualTo(1);
        // Never auto-suspended.
        assertThat(jdbc.sql("SELECT registration_status FROM partners WHERE id = ?::uuid").param(partnerId).query(String.class).single()).isEqualTo("APPROVED");
    }

    @Test
    void failingNotificationChannelIsDeadLetteredWithoutAffectingBusinessData() {
        UUID id = jdbc.sql("""
                INSERT INTO outbox_events(event_type, channel, recipient, payload, max_attempts)
                VALUES ('TEST', 'EMAIL', 'nobody@example.com', '{"message":"hi"}'::jsonb, 1) RETURNING id""").query(UUID.class).single();
        outbox.dispatchBatch(500);
        assertThat(jdbc.sql("SELECT status FROM outbox_events WHERE id = ?").param(id).query(String.class).single()).isEqualTo("DEAD");
    }

    @Test
    void commissionPrecedencePartnerBeatsSubcategoryBeatsDefault() {
        UUID plumbing = jdbc.sql("SELECT id FROM subcategories WHERE code = 'PLUMBING'").query(UUID.class).single();
        UUID category = jdbc.sql("SELECT category_id FROM subcategories WHERE code = 'PLUMBING'").query(UUID.class).single();
        UUID partner = jdbc.sql("SELECT p.id FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = '+919000000023'").query(UUID.class).single();
        UUID city = jdbc.sql("SELECT id FROM cities WHERE code = 'HYD'").query(UUID.class).single();
        assertThat(commissions.resolve(null, null, plumbing, category, city).ratePercent()).isEqualByComparingTo("5.00");
        assertThat(commissions.resolve(null, null, null, UUID.randomUUID(), city).scopeType()).isEqualTo("DEFAULT");
        jdbc.sql("INSERT INTO commission_rules(scope_type, scope_id, rate_percent) VALUES ('PARTNER', ?, 3.50)").param(partner).update();
        assertThat(commissions.resolve(partner, null, plumbing, category, city).ratePercent()).isEqualByComparingTo("3.50");
        jdbc.sql("UPDATE commission_rules SET active = FALSE WHERE scope_type = 'PARTNER' AND scope_id = ?").param(partner).update();
        assertThat(CommissionService.commissionOn(new BigDecimal("999.99"), new BigDecimal("6"))).isEqualByComparingTo("60.00");
    }

    @Test
    void customerCancellationAfterPaymentCreatesPendingRefundNotAutomaticRefund() {
        ApiClient api = new ApiClient(mvc, mapper);
        FlowHelper.Prepared p = new FlowHelper(api, jdbc).toCustomerApproval(1, false);
        var accept = api.post("/api/v1/customer/requests/" + p.requestId() + "/quotations/" + p.quotationIds().get(0) + "/accept",
                p.customerToken(), Map.of("payment_method", "ONLINE"), ApiClient.key());
        api.post("/api/v1/customer/payments/" + accept.data().path("payment").path("id").asText() + "/simulate", p.customerToken(), Map.of("outcome", "SUCCESS"));
        var cancel = api.post("/api/v1/customer/requests/" + p.requestId() + "/cancel", p.customerToken(), Map.of("reason", "Fixed it myself"));
        assertThat(cancel.data().path("refund_review").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(p.requestId()).query(String.class).single()).isEqualTo("REFUND_PENDING");
        String refundId = jdbc.sql("SELECT r.id::text FROM refunds r JOIN payments p ON p.id = r.payment_id WHERE p.request_id = ?::uuid")
                .param(p.requestId()).query(String.class).single();
        assertThat(jdbc.sql("SELECT status FROM refunds WHERE id = ?::uuid").param(refundId).query(String.class).single()).isEqualTo("PENDING");
        String admin = api.login("9000000001", "ADMIN");
        assertThat(api.post("/api/v1/admin/finance/refunds/" + refundId + "/approve", admin, Map.of()).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(p.requestId()).query(String.class).single()).isEqualTo("REFUNDED");
        // The partner's assignment was cancelled by the customer, which does not count against the partner.
        assertThat(jdbc.sql("SELECT cancelled_by FROM assignments WHERE request_id = ?::uuid").param(p.requestId()).query(String.class).single()).isEqualTo("CUSTOMER");
    }
}
