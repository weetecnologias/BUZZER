package com.buzzer.payment;

import com.buzzer.common.BuzzerProperties;
import com.buzzer.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * TEST MODE payment provider. No money moves. It behaves like a real gateway from the backend's point of
 * view: server-created orders, HMAC-SHA256 signed webhooks, duplicate deliveries, failures. It is the only
 * provider until real gateway credentials are configured, and every payment it handles is flagged test_mode.
 */
@Component
@ConditionalOnProperty(prefix = "buzzer.payments", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentProvider implements PaymentProvider {
    private final BuzzerProperties props;
    private final Json json;

    public MockPaymentProvider(BuzzerProperties props, Json json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public boolean testMode() {
        return true;
    }

    @Override
    public CreatedOrder createOrder(String paymentNumber, BigDecimal amount, String currency) {
        String orderId = "mock_order_" + UUID.randomUUID().toString().replace("-", "");
        return new CreatedOrder(orderId, Map.of("provider", "MOCK", "orderId", orderId, "amount", amount, "currency", currency,
                "testMode", true, "note", "TEST MODE - no real payment is taken"));
    }

    @Override
    public boolean verifySignature(String rawBody, String signature) {
        if (signature == null) return false;
        return MessageDigest.isEqual(sign(rawBody).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    public String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.payments().webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public WebhookEvent parse(String rawBody) {
        JsonNode n = json.tree(rawBody);
        return new WebhookEvent(n.path("id").asText(), n.path("type").asText(), n.path("orderId").asText(),
                n.path("paymentId").asText(null), new BigDecimal(n.path("amount").asText("0")), n.path("failureReason").asText(null));
    }

    @Override
    public String refund(String providerPaymentId, BigDecimal amount) {
        return "mock_refund_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Builds a webhook body as the mock gateway would send it. */
    public String buildEvent(String type, String orderId, BigDecimal amount, String failureReason) {
        return json.write(Map.of(
                "id", "evt_" + UUID.randomUUID().toString().replace("-", ""),
                "type", type,
                "orderId", orderId,
                "paymentId", "mock_pay_" + UUID.randomUUID().toString().replace("-", ""),
                "amount", amount.toPlainString(),
                "failureReason", failureReason == null ? "" : failureReason));
    }
}
