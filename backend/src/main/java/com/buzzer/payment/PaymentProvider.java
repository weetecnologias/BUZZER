package com.buzzer.payment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Payment gateway adapter. Real gateways (Razorpay, Cashfree, PayU, Stripe) are added as further
 * implementations once merchant credentials and contracts are in place.
 */
public interface PaymentProvider {
    String name();

    boolean testMode();

    /** Creates a server-side order. Returns the provider order id and any client checkout parameters. */
    CreatedOrder createOrder(String paymentNumber, BigDecimal amount, String currency);

    /** Verifies the webhook signature over the raw request body. */
    boolean verifySignature(String rawBody, String signature);

    /** Parses a verified webhook body. */
    WebhookEvent parse(String rawBody);

    /** Initiates a refund; returns provider refund id. */
    String refund(String providerPaymentId, BigDecimal amount);

    record CreatedOrder(String providerOrderId, Map<String, Object> checkout) {}

    record WebhookEvent(String eventId, String type, String providerOrderId, String providerPaymentId, BigDecimal amount, String failureReason) {}
}
