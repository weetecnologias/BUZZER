package com.buzzer.payment;

import com.buzzer.assignment.AssignmentService;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import com.buzzer.finance.LedgerService;
import com.buzzer.notification.NotificationService;
import com.buzzer.ops.OpsExceptionService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payments: server-created orders, verified webhooks, idempotency, duplicate-callback protection,
 * reconciliation checks and refunds. The client's "payment success" is never trusted - only a verified
 * provider webhook marks a payment as SUCCEEDED. A payment failure never deletes or loses the request.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public record PaymentView(UUID id, String paymentNumber, String status, String method, BigDecimal amount, String provider,
                              String providerOrderId, boolean testMode, Map<String, Object> checkout, String failureReason) {}

    private record Payment(UUID id, String paymentNumber, UUID requestId, UUID quotationId, UUID payerUserId, BigDecimal amount,
                           String status, String method, String provider, String providerOrderId, String providerPaymentId, UUID orderId) {}

    private record Quote(UUID id, UUID requestId, UUID partnerId, String status, BigDecimal totalAmount, boolean payOnDeliveryAllowed,
                         boolean expired) {}

    private final JdbcClient jdbc;
    private final BusinessIds ids;
    private final PaymentProvider provider;
    private final RequestRepository requests;
    private final AssignmentService assignments;
    private final LedgerService ledger;
    private final NotificationService notifications;
    private final OpsExceptionService ops;
    private final SettingsService settings;
    private final AuditService audit;
    private final Json json;
    private final org.springframework.context.ApplicationEventPublisher events;

    /** Published inside the webhook transaction when a marketplace order's online payment succeeds or fails. */
    public record OrderPaymentEvent(UUID orderId, UUID paymentId, String paymentNumber, boolean succeeded, String failureReason) {}

    public PaymentService(JdbcClient jdbc, BusinessIds ids, PaymentProvider provider, RequestRepository requests,
                          AssignmentService assignments, LedgerService ledger, NotificationService notifications,
                          OpsExceptionService ops, SettingsService settings, AuditService audit, Json json,
                          org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc;
        this.ids = ids;
        this.provider = provider;
        this.requests = requests;
        this.assignments = assignments;
        this.ledger = ledger;
        this.notifications = notifications;
        this.ops = ops;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
    }

    /** Customer accepts a quotation and chooses how to pay. */
    @Transactional
    public Map<String, Object> acceptQuotation(UUID requestId, UUID quotationId, UUID customerUserId, String method, String idempotencyKey) {
        if (!List.of("ONLINE", "PAY_ON_DELIVERY").contains(method)) throw BusinessException.invalid("Payment method must be ONLINE or PAY_ON_DELIVERY");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw BusinessException.invalid("Idempotency-Key header is required");
        }
        String key = "accept:" + customerUserId + ":" + idempotencyKey;
        var existing = byIdempotencyKey(key);
        if (existing != null) return Map.of("payment", existing, "duplicate", true);

        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customerUserId)) throw BusinessException.forbidden("Not your request");
        if (r.statusEnum() != RequestStatus.CUSTOMER_APPROVAL) throw BusinessException.transition(r.status(), "ACCEPTED");
        Quote q = quote(quotationId, requestId);
        if (!"SHARED".equals(q.status())) throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "This quotation is no longer available");
        if (q.expired()) throw new BusinessException(ErrorCode.QUOTATION_EXPIRED, "This quotation has expired. Ask your agent for an updated quote.");

        if ("PAY_ON_DELIVERY".equals(method)) {
            if (!settings.getBoolean("payments.pay_on_delivery_enabled", true) || !q.payOnDeliveryAllowed()) {
                throw new BusinessException(ErrorCode.PAY_ON_DELIVERY_NOT_ALLOWED, "Pay on delivery is not available for this service");
            }
            UUID paymentId = insertPayment(r.id(), q.id(), customerUserId, q.totalAmount(), "PAY_ON_DELIVERY", "CASH", null, "COLLECT_ON_DELIVERY", key, false);
            assignments.assignExclusive(requestId, quotationId, customerUserId, "CUSTOMER", "Customer accepted quotation (pay on delivery)");
            return Map.of("payment", view(paymentId, null), "duplicate", false);
        }

        jdbc.sql("UPDATE quotations SET status = 'ACCEPTED', decided_at = now() WHERE id = :q").param("q", quotationId).update();
        requests.transition(r, RequestStatus.PAYMENT_PENDING, customerUserId, "CUSTOMER", "Customer accepted quotation; awaiting online payment");
        return Map.of("payment", createOnlinePayment(r, q, customerUserId, key), "duplicate", false);
    }

    /** Retry after a failed/abandoned online payment. The request and accepted quotation are kept. */
    @Transactional
    public PaymentView retry(UUID requestId, UUID customerUserId, String idempotencyKey) {
        String key = "retry:" + customerUserId + ":" + idempotencyKey;
        var existing = byIdempotencyKey(key);
        if (existing != null) return existing;
        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customerUserId)) throw BusinessException.forbidden("Not your request");
        if (r.statusEnum() != RequestStatus.PAYMENT_PENDING) throw BusinessException.transition(r.status(), "PAYMENT_PENDING");
        Integer succeeded = jdbc.sql("SELECT count(*) FROM payments WHERE request_id = :r AND status = 'SUCCEEDED'").param("r", requestId)
                .query(Integer.class).single();
        if (succeeded > 0) throw new BusinessException(ErrorCode.DUPLICATE, "This request is already paid");
        UUID quotationId = jdbc.sql("SELECT id FROM quotations WHERE request_id = :r AND status = 'ACCEPTED'").param("r", requestId)
                .query(UUID.class).single();
        return createOnlinePayment(r, quote(quotationId, requestId), customerUserId, key);
    }

    /** Customer changes their mind before paying: back to comparing quotations. */
    @Transactional
    public void withdrawAcceptance(UUID requestId, UUID customerUserId) {
        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customerUserId)) throw BusinessException.forbidden("Not your request");
        if (r.statusEnum() != RequestStatus.PAYMENT_PENDING) throw BusinessException.transition(r.status(), "CUSTOMER_APPROVAL");
        Integer succeeded = jdbc.sql("SELECT count(*) FROM payments WHERE request_id = :r AND status IN ('SUCCEEDED','PENDING')")
                .param("r", requestId).query(Integer.class).single();
        if (succeeded > 0) throw new BusinessException(ErrorCode.CONFLICT, "A payment is already in progress or completed");
        jdbc.sql("UPDATE quotations SET status = 'SHARED', decided_at = NULL WHERE request_id = :r AND status = 'ACCEPTED'").param("r", requestId).update();
        jdbc.sql("UPDATE payments SET status = 'FAILED', failure_reason = COALESCE(failure_reason, 'Abandoned by customer') WHERE request_id = :r AND status = 'CREATED'")
                .param("r", requestId).update();
        requests.transition(r, RequestStatus.CUSTOMER_APPROVAL, customerUserId, "CUSTOMER", "Customer withdrew acceptance before payment");
    }

    private PaymentView createOnlinePayment(RequestRepository.Row r, Quote q, UUID customerUserId, String key) {
        UUID paymentId = insertPayment(r.id(), q.id(), customerUserId, q.totalAmount(), "ONLINE", provider.name(), null, "CREATED", key, provider.testMode());
        String number = jdbc.sql("SELECT payment_number FROM payments WHERE id = :id").param("id", paymentId).query(String.class).single();
        try {
            PaymentProvider.CreatedOrder order = provider.createOrder(number, q.totalAmount(), "INR");
            jdbc.sql("UPDATE payments SET provider_order_id = :o, status = 'PENDING', updated_at = now() WHERE id = :id")
                    .param("o", order.providerOrderId()).param("id", paymentId).update();
            return view(paymentId, order.checkout());
        } catch (RuntimeException e) {
            // Gateway down: keep everything, mark the attempt failed, let the customer retry.
            log.warn("Payment provider failed to create order for {}: {}", number, e.getMessage());
            jdbc.sql("UPDATE payments SET status = 'FAILED', failure_reason = :f, updated_at = now() WHERE id = :id")
                    .param("f", "Payment gateway unavailable").param("id", paymentId).update();
            ops.raise("PAYMENT_GATEWAY_ERROR", "HIGH", "Payment gateway failed to create order", "PAYMENT", paymentId,
                    "Check gateway health", "gateway-error:" + provider.name(), Map.of("error", String.valueOf(e.getMessage())));
            return view(paymentId, null);
        }
    }

    /**
     * Webhook entry point. Signature verified over the raw body, event stored once (duplicates ignored),
     * then processed in the same transaction - if processing fails the whole thing rolls back and the
     * provider's retry re-delivers it.
     */
    @Transactional
    public String handleWebhook(String providerName, String rawBody, String signature) {
        if (!provider.name().equalsIgnoreCase(providerName)) throw BusinessException.notFound("Payment provider");
        if (!provider.verifySignature(rawBody, signature)) {
            log.warn("Rejected {} webhook with invalid signature", providerName);
            throw new BusinessException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "Invalid signature");
        }
        PaymentProvider.WebhookEvent ev = provider.parse(rawBody);
        int inserted = jdbc.sql("""
                INSERT INTO payment_webhook_events(provider, event_id, event_type, payload, signature_valid)
                VALUES (:p, :e, :t, CAST(:body AS jsonb), TRUE) ON CONFLICT (provider, event_id) DO NOTHING""")
                .param("p", provider.name()).param("e", ev.eventId()).param("t", ev.type()).param("body", rawBody).update();
        if (inserted == 0) return "DUPLICATE_IGNORED";

        // Lock order is always request -> payment (same as acceptQuotation) to avoid deadlocks.
        UUID requestId = jdbc.sql("SELECT request_id FROM payments WHERE provider = :p AND provider_order_id = :o")
                .param("p", provider.name()).param("o", ev.providerOrderId()).query(UUID.class).optional().orElse(null);
        if (requestId != null) requests.lock(requestId);
        Payment p = jdbc.sql("""
                SELECT id, payment_number, request_id, quotation_id, payer_user_id, amount, status, method, provider, provider_order_id,
                       provider_payment_id, order_id FROM payments WHERE provider = :p AND provider_order_id = :o FOR UPDATE""")
                .param("p", provider.name()).param("o", ev.providerOrderId()).query(Payment.class).optional().orElse(null);
        String result;
        if (p == null) {
            ops.raise("PAYMENT_RECONCILIATION_MISMATCH", "HIGH", "Webhook for unknown order " + ev.providerOrderId(), "PAYMENT", null,
                    "Investigate with gateway", "unknown-order:" + ev.providerOrderId(), Map.of("eventId", ev.eventId()));
            result = "UNKNOWN_ORDER";
        } else if ("payment.succeeded".equals(ev.type())) {
            result = onSucceeded(p, ev);
        } else if ("payment.failed".equals(ev.type())) {
            result = onFailed(p, ev);
        } else {
            result = "IGNORED_EVENT_TYPE";
        }
        jdbc.sql("UPDATE payment_webhook_events SET processed_at = now(), processing_error = :e WHERE provider = :p AND event_id = :id")
                .param("e", result.startsWith("OK") ? null : result).param("p", provider.name()).param("id", ev.eventId()).update();
        return result;
    }

    private String onSucceeded(Payment p, PaymentProvider.WebhookEvent ev) {
        if ("SUCCEEDED".equals(p.status())) return "OK_ALREADY_SUCCEEDED";
        if (ev.amount().compareTo(p.amount()) != 0) {
            ops.raise("PAYMENT_RECONCILIATION_MISMATCH", "URGENT", "Amount mismatch on " + p.paymentNumber(), "PAYMENT", p.id(),
                    "Reconcile with gateway before releasing the job", "amount-mismatch:" + p.id(),
                    Map.of("expected", p.amount().toPlainString(), "received", ev.amount().toPlainString()));
            return "AMOUNT_MISMATCH";
        }
        jdbc.sql("UPDATE payments SET status = 'SUCCEEDED', provider_payment_id = :pp, failure_reason = NULL, updated_at = now() WHERE id = :id")
                .param("pp", ev.providerPaymentId()).param("id", p.id()).update();
        ledger.post(p.requestId(), "PAYMENT", p.id(), List.of(new LedgerService.Entry("CUSTOMER_PAYMENT", "CREDIT", p.amount(), null,
                "Online payment " + p.paymentNumber() + (provider.testMode() ? " (TEST MODE)" : ""))));
        issueReceipt(p);
        audit.record(p.payerUserId(), "SYSTEM", "PAYMENT_SUCCEEDED", "PAYMENT", p.id(), Map.of("amount", p.amount().toPlainString()));

        if (p.orderId() != null) {
            // Marketplace order: the order module moves it to PAID and tells the shop.
            events.publishEvent(new OrderPaymentEvent(p.orderId(), p.id(), p.paymentNumber(), true, null));
            return "OK_SUCCEEDED";
        }
        if (p.requestId() != null) {
            RequestRepository.Row r = requests.lock(p.requestId());
            if (r.statusEnum() == RequestStatus.PAYMENT_PENDING) {
                requests.transition(r, RequestStatus.PAYMENT_CONFIRMED, null, "SYSTEM", "Payment " + p.paymentNumber() + " confirmed by gateway");
                assignments.assignExclusive(r.id(), p.quotationId(), null, "SYSTEM", "Auto-assigned after confirmed payment");
            } else {
                // Paid after the request moved on (e.g. cancelled): needs a refund decision, never silently kept.
                ops.raise("PAYMENT_AFTER_CLOSE", "HIGH", "Payment received for request in status " + r.status(), "PAYMENT", p.id(),
                        "Review and refund if appropriate", "late-payment:" + p.id(), Map.of("request", r.requestNumber()));
            }
            notifications.notifyUser(p.payerUserId(), "Payment received", "We received Rs " + p.amount().toPlainString() + " for " + r.requestNumber() + ".",
                    "PAYMENT", "PAYMENT", p.id());
        }
        return "OK_SUCCEEDED";
    }

    private String onFailed(Payment p, PaymentProvider.WebhookEvent ev) {
        if ("SUCCEEDED".equals(p.status())) return "OK_IGNORED_FAILURE_AFTER_SUCCESS";
        jdbc.sql("UPDATE payments SET status = 'FAILED', failure_reason = :f, updated_at = now() WHERE id = :id")
                .param("f", ev.failureReason() == null || ev.failureReason().isBlank() ? "Payment failed" : ev.failureReason())
                .param("id", p.id()).update();
        notifications.notifyUser(p.payerUserId(), "Payment failed", "Your payment did not go through. Your request is safe - you can retry anytime.",
                "PAYMENT", "PAYMENT", p.id());
        if (p.orderId() != null) {
            events.publishEvent(new OrderPaymentEvent(p.orderId(), p.id(), p.paymentNumber(), false,
                    ev.failureReason() == null || ev.failureReason().isBlank() ? "Payment failed" : ev.failureReason()));
        }
        return "OK_FAILED";
    }

    /** TEST MODE ONLY: simulates the gateway calling our webhook, through the real verification path. */
    @Transactional
    public String simulate(UUID paymentId, UUID customerUserId, String outcome) {
        if (!(provider instanceof MockPaymentProvider mock)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Payment simulation is only available in test mode");
        }
        Payment p = jdbc.sql("""
                SELECT id, payment_number, request_id, quotation_id, payer_user_id, amount, status, method, provider, provider_order_id,
                       provider_payment_id, order_id FROM payments WHERE id = :id""").param("id", paymentId).query(Payment.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Payment"));
        if (!p.payerUserId().equals(customerUserId)) throw BusinessException.notFound("Payment");
        if (p.providerOrderId() == null) throw BusinessException.invalid("Payment has no gateway order");
        String body = mock.buildEvent("SUCCESS".equals(outcome) ? "payment.succeeded" : "payment.failed", p.providerOrderId(), p.amount(),
                "SUCCESS".equals(outcome) ? null : "Card declined (simulated)");
        return handleWebhook(mock.name(), body, mock.sign(body));
    }

    // ----- Refunds (never automatic and blanket: created as PENDING, approved by finance/admin) -----

    public UUID requestRefund(UUID paymentId, BigDecimal amount, String reason, UUID by) {
        Payment p = jdbc.sql("""
                SELECT id, payment_number, request_id, quotation_id, payer_user_id, amount, status, method, provider, provider_order_id,
                       provider_payment_id, order_id FROM payments WHERE id = :id FOR UPDATE""").param("id", paymentId).query(Payment.class).single();
        if (!List.of("SUCCEEDED", "PARTIALLY_REFUNDED").contains(p.status())) throw BusinessException.invalid("Only successful payments can be refunded");
        BigDecimal refunded = jdbc.sql("SELECT COALESCE(sum(amount),0) FROM refunds WHERE payment_id = :p AND status NOT IN ('REJECTED','FAILED')")
                .param("p", paymentId).query(BigDecimal.class).single();
        if (amount.signum() <= 0 || refunded.add(amount).compareTo(p.amount()) > 0) throw BusinessException.invalid("Refund exceeds the refundable amount");
        UUID id = jdbc.sql("""
                INSERT INTO refunds(refund_number, payment_id, amount, reason, status, requested_by) VALUES (:n, :p, :a, :r, 'PENDING', :by) RETURNING id""")
                .param("n", ids.next(BusinessIds.Kind.REFUND)).param("p", paymentId).param("a", amount).param("r", reason).param("by", by)
                .query(UUID.class).single();
        jdbc.sql("UPDATE payments SET status = 'REFUND_PENDING', updated_at = now() WHERE id = :id").param("id", paymentId).update();
        return id;
    }

    @Transactional
    public void approveRefund(UUID refundId, UUID adminUserId) {
        record Refund(UUID id, UUID paymentId, BigDecimal amount, String status) {}
        Refund rf = jdbc.sql("SELECT id, payment_id, amount, status FROM refunds WHERE id = :id FOR UPDATE").param("id", refundId)
                .query(Refund.class).optional().orElseThrow(() -> BusinessException.notFound("Refund"));
        if (!"PENDING".equals(rf.status())) throw BusinessException.transition(rf.status(), "COMPLETED");
        Payment p = jdbc.sql("""
                SELECT id, payment_number, request_id, quotation_id, payer_user_id, amount, status, method, provider, provider_order_id,
                       provider_payment_id, order_id FROM payments WHERE id = :id FOR UPDATE""").param("id", rf.paymentId()).query(Payment.class).single();
        provider.refund(p.providerPaymentId(), rf.amount());
        jdbc.sql("UPDATE refunds SET status = 'COMPLETED', approved_by = :a, updated_at = now() WHERE id = :id").param("a", adminUserId).param("id", refundId).update();
        BigDecimal refunded = jdbc.sql("SELECT COALESCE(sum(amount),0) FROM refunds WHERE payment_id = :p AND status = 'COMPLETED'")
                .param("p", p.id()).query(BigDecimal.class).single();
        boolean full = refunded.compareTo(p.amount()) >= 0;
        jdbc.sql("UPDATE payments SET status = :s, updated_at = now() WHERE id = :id").param("s", full ? "REFUNDED" : "PARTIALLY_REFUNDED")
                .param("id", p.id()).update();
        ledger.post(p.requestId(), "REFUND", refundId, List.of(new LedgerService.Entry("REFUND", "DEBIT", rf.amount(), null, "Refund for " + p.paymentNumber())));
        audit.record(adminUserId, "FINANCE", "REFUND_APPROVED", "REFUND", refundId, Map.of("amount", rf.amount().toPlainString()));
        if (full && p.requestId() != null) {
            RequestRepository.Row r = requests.lock(p.requestId());
            if (r.isV1() && r.statusEnum() == RequestStatus.REFUND_PENDING) requests.transition(r, RequestStatus.REFUNDED, adminUserId, "FINANCE", "Refund completed");
        }
        notifications.notifyUser(p.payerUserId(), "Refund processed", "Rs " + rf.amount().toPlainString() + " has been refunded.", "PAYMENT", "REFUND", refundId);
    }

    // ----- Marketplace orders ----------------------------------------------------------------------------

    /** Online payment for an order (server-created gateway order; only the verified webhook marks it paid). */
    public PaymentView createOrderPayment(UUID orderId, UUID requestId, UUID payer, BigDecimal amount, String key) {
        var existing = byIdempotencyKey(key);
        if (existing != null) return existing;
        UUID paymentId = insertPayment("ORDER", requestId, null, orderId, payer, amount, "ONLINE", provider.name(), "CREATED", key, provider.testMode());
        String number = jdbc.sql("SELECT payment_number FROM payments WHERE id = :id").param("id", paymentId).query(String.class).single();
        try {
            PaymentProvider.CreatedOrder order = provider.createOrder(number, amount, "INR");
            jdbc.sql("UPDATE payments SET provider_order_id = :o, status = 'PENDING', updated_at = now() WHERE id = :id")
                    .param("o", order.providerOrderId()).param("id", paymentId).update();
            return view(paymentId, order.checkout());
        } catch (RuntimeException e) {
            log.warn("Payment provider failed to create order for {}: {}", number, e.getMessage());
            jdbc.sql("UPDATE payments SET status = 'FAILED', failure_reason = :f, updated_at = now() WHERE id = :id")
                    .param("f", "Payment gateway unavailable").param("id", paymentId).update();
            ops.raise("PAYMENT_GATEWAY_ERROR", "HIGH", "Payment gateway failed to create order", "PAYMENT", paymentId,
                    "Check gateway health", "gateway-error:" + provider.name(), Map.of("error", String.valueOf(e.getMessage())));
            return view(paymentId, null);
        }
    }

    /** Pay at pickup / on delivery: the shop collects the amount when handing over the order. */
    public PaymentView recordCollectOnDelivery(UUID orderId, UUID requestId, UUID payer, BigDecimal amount, String key) {
        var existing = byIdempotencyKey(key);
        if (existing != null) return existing;
        return view(insertPayment("ORDER", requestId, null, orderId, payer, amount, "PAY_ON_DELIVERY", "CASH", "COLLECT_ON_DELIVERY", key, false), null);
    }

    /** The shop handed over the order and collected the cash. */
    public void markCollected(UUID orderId) {
        jdbc.sql("UPDATE payments SET status = 'SUCCEEDED', updated_at = now() WHERE order_id = :o AND status = 'COLLECT_ON_DELIVERY'")
                .param("o", orderId).update();
    }

    /** Closes open payment attempts; when the order was already paid online, opens a refund (PENDING, approved by finance). */
    public UUID closeOrderPayments(UUID orderId, String reason, UUID by) {
        jdbc.sql("""
                UPDATE payments SET status = 'FAILED', failure_reason = :r, updated_at = now()
                WHERE order_id = :o AND status IN ('CREATED','PENDING','COLLECT_ON_DELIVERY')""")
                .param("r", reason).param("o", orderId).update();
        record Paid(UUID id, BigDecimal amount) {}
        Paid paid = jdbc.sql("SELECT id, amount FROM payments WHERE order_id = :o AND status = 'SUCCEEDED' ORDER BY created_at DESC LIMIT 1")
                .param("o", orderId).query(Paid.class).optional().orElse(null);
        return paid == null ? null : requestRefund(paid.id(), paid.amount(), reason, by);
    }

    public List<Map<String, Object>> forOrder(UUID orderId) {
        return jdbc.sql("""
                SELECT id, payment_number, method, provider, amount, status, failure_reason, test_mode, created_at
                FROM payments WHERE order_id = :o ORDER BY created_at DESC""").param("o", orderId).query().listOfRows();
    }

    public List<Map<String, Object>> forRequest(UUID requestId) {
        return jdbc.sql("""
                SELECT id, payment_number, method, provider, amount, status, failure_reason, test_mode, created_at
                FROM payments WHERE request_id = :r ORDER BY created_at DESC""").param("r", requestId).query().listOfRows();
    }

    public List<Map<String, Object>> invoicesForRequest(UUID requestId) {
        return jdbc.sql("""
                SELECT id, invoice_number, invoice_type, line_items::text AS line_items, subtotal, tax_amount, discount_amount, total_amount, issued_at
                FROM invoices WHERE request_id = :r ORDER BY issued_at""").param("r", requestId).query().listOfRows();
    }

    private void issueReceipt(Payment p) {
        jdbc.sql("""
                INSERT INTO invoices(invoice_number, invoice_type, request_id, payment_id, customer_user_id, line_items, subtotal, tax_amount, total_amount)
                VALUES (:n, 'RECEIPT', :r, :p, :c, CAST(:items AS jsonb), :amt, 0, :amt)""")
                .param("n", ids.next(BusinessIds.Kind.INVOICE)).param("r", p.requestId()).param("p", p.id()).param("c", p.payerUserId())
                .param("items", json.write(List.of(Map.of("description", "Payment " + p.paymentNumber(), "amount", p.amount()))))
                .param("amt", p.amount()).update();
    }

    private UUID insertPayment(UUID requestId, UUID quotationId, UUID payer, BigDecimal amount, String method, String providerName,
                               String orderId, String status, String key, boolean testMode) {
        return jdbc.sql("""
                INSERT INTO payments(payment_number, purpose, request_id, quotation_id, payer_user_id, amount, method, provider,
                  provider_order_id, status, idempotency_key, test_mode)
                VALUES (:n, 'SERVICE', :r, :q, :u, :a, :m, :prov, :o, :s, :k, :t) RETURNING id""")
                .param("n", ids.next(BusinessIds.Kind.PAYMENT)).param("r", requestId).param("q", quotationId).param("u", payer)
                .param("a", amount).param("m", method).param("prov", providerName).param("o", orderId).param("s", status)
                .param("k", key).param("t", testMode).query(UUID.class).single();
    }

    private UUID insertPayment(String purpose, UUID requestId, UUID quotationId, UUID orderId, UUID payer, BigDecimal amount, String method,
                               String providerName, String status, String key, boolean testMode) {
        return jdbc.sql("""
                INSERT INTO payments(payment_number, purpose, request_id, quotation_id, order_id, payer_user_id, amount, method, provider,
                  status, idempotency_key, test_mode)
                VALUES (:n, :purpose, :r, :q, :ord, :u, :a, :m, :prov, :s, :k, :t) RETURNING id""")
                .param("n", ids.next(BusinessIds.Kind.PAYMENT)).param("purpose", purpose).param("r", requestId).param("q", quotationId)
                .param("ord", orderId).param("u", payer).param("a", amount).param("m", method).param("prov", providerName)
                .param("s", status).param("k", key).param("t", testMode).query(UUID.class).single();
    }

    private PaymentView byIdempotencyKey(String key) {
        return jdbc.sql("SELECT id FROM payments WHERE idempotency_key = :k").param("k", key).query(UUID.class).optional()
                .map(id -> view(id, null)).orElse(null);
    }

    private PaymentView view(UUID id, Map<String, Object> checkout) {
        record Row(UUID id, String paymentNumber, String status, String method, BigDecimal amount, String provider, String providerOrderId,
                   boolean testMode, String failureReason) {}
        Row r = jdbc.sql("SELECT id, payment_number, status, method, amount, provider, provider_order_id, test_mode, failure_reason FROM payments WHERE id = :id")
                .param("id", id).query(Row.class).single();
        return new PaymentView(r.id(), r.paymentNumber(), r.status(), r.method(), r.amount(), r.provider(), r.providerOrderId(), r.testMode(),
                checkout, r.failureReason());
    }

    private Quote quote(UUID quotationId, UUID requestId) {
        Quote q = jdbc.sql("""
                SELECT id, request_id, partner_id, status, total_amount, pay_on_delivery_allowed, (valid_until < now()) AS expired
                FROM quotations WHERE id = :q FOR UPDATE""").param("q", quotationId).query(Quote.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Quotation"));
        if (!q.requestId().equals(requestId)) throw BusinessException.notFound("Quotation");
        return q;
    }
}
