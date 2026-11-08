package com.buzzer.order;

import com.buzzer.agent.AgentAllocationService;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import com.buzzer.finance.CommissionService;
import com.buzzer.finance.LedgerService;
import com.buzzer.notification.NotificationService;
import com.buzzer.ops.OpsExceptionService;
import com.buzzer.payment.PaymentService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestStatus;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Marketplace orders. Two ways in:
 * <ul>
 *   <li><b>Direct purchase</b>: one-shop product cart -> checkout (price and availability re-validated, idempotent).</li>
 *   <li><b>Quotation request accepted</b>: the customer accepts ONE shop's quote; the request becomes an order (one accepted
 *       vendor per RFQ, enforced under a row lock and by a unique index).</li>
 * </ul>
 * Prices, taxes, product and fulfilment details are frozen on the order (immutable snapshots, DB-enforced). The order status
 * is mirrored on the request hub so "My Requests", agents and admins follow one timeline. Payment is online (only a verified
 * gateway webhook marks it paid) or pay at pickup / on delivery. The shop completes the order with the customer's code.
 */
@Service
public class OrderService {
    public record CheckoutInput(String fulfilment, java.util.UUID addressId, String paymentMethod, String note) {}

    private record Ord(UUID id, String orderNumber, UUID requestId, UUID customerUserId, UUID partnerId, String status, String fulfilment,
                       String paymentMethod, BigDecimal totalAmount, BigDecimal deliveryFee, String completionCode, int codeAttempts, String source) {
        OrderStatus statusEnum() {
            return OrderStatus.valueOf(status);
        }
    }

    private static final String ORD_COLS = """
            id, order_number, request_id, customer_user_id, partner_id, status, fulfilment, payment_method, total_amount, delivery_fee,
            completion_code, code_attempts, source""";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final Json json;
    private final BusinessIds ids;
    private final ProductCartService carts;
    private final RequestRepository requests;
    private final PaymentService payments;
    private final NotificationService notifications;
    private final AgentAllocationService allocation;
    private final CommissionService commissions;
    private final LedgerService ledger;
    private final SettingsService settings;
    private final OpsExceptionService ops;
    private final AuditService audit;

    public OrderService(JdbcClient jdbc, Json json, BusinessIds ids, ProductCartService carts, RequestRepository requests, PaymentService payments,
                        NotificationService notifications, AgentAllocationService allocation, CommissionService commissions, LedgerService ledger,
                        SettingsService settings, OpsExceptionService ops, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.ids = ids;
        this.carts = carts;
        this.requests = requests;
        this.payments = payments;
        this.notifications = notifications;
        this.allocation = allocation;
        this.commissions = commissions;
        this.ledger = ledger;
        this.settings = settings;
        this.ops = ops;
        this.audit = audit;
    }

    // ===== 1. Direct purchase =============================================================================

    @Transactional
    public Map<String, Object> checkout(UUID customer, CheckoutInput in, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        UUID dup = existing(customer, "direct:" + key);
        if (dup != null) return withPayment(dup, customer, true);

        UUID cartId = jdbc.sql("SELECT id FROM product_carts WHERE customer_user_id = :c FOR UPDATE").param("c", customer).query(UUID.class)
                .optional().orElseThrow(() -> BusinessException.invalid("Your cart is empty"));
        String fulfilment = fulfilment(in == null ? null : in.fulfilment());
        UUID addressId = in == null ? null : in.addressId();
        if ("VENDOR_DELIVERY".equals(fulfilment) && addressId == null) throw BusinessException.invalid("Choose the delivery address");
        ProductCartService.Quote q = carts.quote(customer, "VENDOR_DELIVERY".equals(fulfilment) ? addressId : null);
        if (q == null || q.lines().isEmpty()) throw BusinessException.invalid("Your cart is empty");
        if (q.hasIssues()) {
            List<Map<String, Object>> bad = q.lines().stream().filter(l -> !l.issues().isEmpty())
                    .map(l -> Map.<String, Object>of("listing_id", l.listingId(), "product_name", l.productName(), "issues", l.issues())).toList();
            throw new BusinessException(ErrorCode.ITEM_UNAVAILABLE, "Some items can't be ordered - please update your cart", Map.of("items", bad));
        }
        if (q.hasPriceChanges()) {
            List<Map<String, Object>> changed = q.lines().stream().filter(ProductCartService.Line::priceChanged)
                    .map(l -> Map.<String, Object>of("listing_id", l.listingId(), "product_name", l.productName(), "old_price", l.seenPrice(),
                            "new_price", l.unitPrice())).toList();
            throw new BusinessException(ErrorCode.PRICE_CHANGED, "Prices changed since you added these items - please review the new prices",
                    Map.of("items", changed));
        }
        ProductCartService.Location loc = q.location();
        if (loc == null) throw new BusinessException(ErrorCode.ITEM_UNAVAILABLE, "This shop has no open location right now");
        BigDecimal deliveryFee = BigDecimal.ZERO.setScale(2);
        Map<String, Object> address = null;
        if ("PICKUP".equals(fulfilment)) {
            if (!loc.pickupEnabled()) throw BusinessException.invalid("This shop does not offer pickup - choose delivery");
        } else {
            if (!q.deliveryAvailable()) throw BusinessException.invalid(q.deliveryUnavailableReason());
            deliveryFee = q.deliveryFee();
            address = address(addressId, customer);
        }
        String method = paymentMethod(in == null ? null : in.paymentMethod());
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > 500) throw BusinessException.invalid("Note must be 500 characters or less");

        BigDecimal total = q.itemsTotal().add(deliveryFee).setScale(2, RoundingMode.HALF_UP);
        ProductCartService.Line first = q.lines().getFirst();
        UUID cityId = address != null ? (UUID) address.get("city_id") : loc.cityId();
        UUID localityId = address != null ? (UUID) address.get("locality_id") : null;
        String status = "ONLINE".equals(method) ? OrderStatus.PAYMENT_PENDING.name() : OrderStatus.PLACED.name();
        StringBuilder summary = new StringBuilder();
        List<Map<String, Object>> lineJson = new ArrayList<>();
        for (ProductCartService.Line l : q.lines()) {
            if (!summary.isEmpty()) summary.append(", ");
            summary.append(l.quantity()).append(" x ").append(l.productName());
            lineJson.add(Map.of("key", "listing:" + l.listingId(), "name", l.productName() + ("Standard".equals(l.variantName()) ? "" : " (" + l.variantName() + ")"),
                    "quantity", l.quantity(), "unit_price", l.unitPrice(), "line_total", l.lineTotal()));
        }
        UUID requestId = createHub(customer, first.categoryId(), first.subcategoryId(), cityId, localityId, addressId, address,
                "Order from " + q.shopName() + ": " + summary + (note == null ? "" : "\nNote: " + note), status, lineJson, total, "order:" + key);

        Map<String, Object> vendor = vendorSnapshot(q.partnerId(), loc.locationId());
        Map<String, Object> fulfilmentSnap = new LinkedHashMap<>();
        fulfilmentSnap.put("method", fulfilment);
        if ("PICKUP".equals(fulfilment)) {
            fulfilmentSnap.put("pickup_location", ProductCartService.locationMap(loc));
        } else {
            fulfilmentSnap.put("delivery_address", address);
            fulfilmentSnap.put("delivery_fee", deliveryFee);
            fulfilmentSnap.put("from_location", ProductCartService.locationMap(loc));
        }
        List<OrderLine> lines = new ArrayList<>();
        for (ProductCartService.Line l : q.lines()) {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("mrp", l.mrp());
            snap.put("stock_status_at_order", l.stockStatus());
            snap.put("stock_updated_at", l.stockUpdatedAt());
            snap.put("moq", l.moq());
            snap.put("lead_time_days", l.leadTimeDays());
            snap.put("specs", l.specs() == null ? Map.of() : json.map(l.specs()));
            lines.add(new OrderLine(l.listingId(), l.productId(), l.variantId(), null, l.productName(), l.variantName(), l.brand(), null, l.unit(),
                    l.quantity(), l.unitPrice(), BigDecimal.ZERO, l.taxRate(), l.taxIncluded(), l.lineTotal(), snap));
        }
        UUID orderId = insertOrder(requestId, customer, q.partnerId(), loc.locationId(), "DIRECT", null, status, fulfilment, method,
                q.itemsTotal(), BigDecimal.ZERO, q.taxIncluded(), true, deliveryFee, total, vendor, fulfilmentSnap, note, "direct:" + key, lines);
        String number = orderNumber(orderId);
        history(requestId, null, status, customer, "CUSTOMER", "Order " + number + " placed with " + q.shopName() + ": " + q.lines().size()
                + " item(s), Rs " + total.toPlainString() + (deliveryFee.signum() > 0 ? " incl. delivery Rs " + deliveryFee.toPlainString() : "")
                + " - " + ("PICKUP".equals(fulfilment) ? "pickup" : "delivery") + ", " + ("ONLINE".equals(method) ? "pay online" : "pay at "
                + ("PICKUP".equals(fulfilment) ? "pickup" : "delivery")));
        PaymentService.PaymentView payment = "ONLINE".equals(method)
                ? payments.createOrderPayment(orderId, requestId, customer, total, "order:" + customer + ":" + key)
                : payments.recordCollectOnDelivery(orderId, requestId, customer, total, "order:" + customer + ":" + key);
        jdbc.sql("DELETE FROM product_cart_items WHERE cart_id = :c").param("c", cartId).update();
        afterPlaced(orderId);
        audit.record(customer, "CUSTOMER", "ORDER_PLACED", "ORDER", orderId, Map.of("order", number, "total", total.toPlainString(), "source", "DIRECT"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", customerOrder(orderId, customer));
        out.put("payment", payment);
        out.put("duplicate", false);
        return out;
    }

    // ===== 2. Accepting one shop's quotation (RFQ -> order) ===================================================

    /** True for quotation requests with structured items (these become orders when a quote is accepted). */
    public boolean isStructuredRfq(UUID requestId) {
        return jdbc.sql("""
                SELECT count(*) FROM service_requests r WHERE r.id = :r AND r.request_type = 'PRODUCT_RFQ'
                  AND EXISTS (SELECT 1 FROM request_items ri WHERE ri.request_id = r.id)""").param("r", requestId).query(Integer.class).single() > 0;
    }

    @Transactional
    public Map<String, Object> acceptQuote(UUID requestId, UUID quotationId, UUID customer, String paymentMethod, String fulfilmentChoice,
                                           String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        UUID dup = existing(customer, "rfq:" + key);
        if (dup != null) return withPayment(dup, customer, true);
        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customer)) throw BusinessException.notFound("Request");
        if (r.statusEnum() != RequestStatus.CUSTOMER_APPROVAL) throw BusinessException.transition(r.status(), "ACCEPTED");
        record Q(UUID id, UUID partnerId, String status, BigDecimal deliveryFee, boolean payOnDeliveryAllowed, boolean expired, String partnerName,
                 String quotationNumber, String terms, String exclusions, Integer leadTimeDays, String lineItems) {}
        Q q = jdbc.sql("""
                SELECT q.id, q.partner_id, q.status, q.visit_fee AS delivery_fee, q.pay_on_delivery_allowed, (q.valid_until < now()) AS expired,
                       p.display_name AS partner_name, q.quotation_number, q.terms, q.exclusions, q.lead_time_days, q.line_items::text AS line_items
                FROM quotations q JOIN partners p ON p.id = q.partner_id WHERE q.id = :q AND q.request_id = :r FOR UPDATE OF q""")
                .param("q", quotationId).param("r", requestId).query(Q.class).optional().orElseThrow(() -> BusinessException.notFound("Quotation"));
        if (!"SHARED".equals(q.status())) throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "This quotation is no longer available");
        if (q.expired()) throw new BusinessException(ErrorCode.QUOTATION_EXPIRED, "This quotation has expired - ask the shop to revise it");
        String method = paymentMethod(paymentMethod);
        if ("PAY_ON_DELIVERY".equals(method) && !q.payOnDeliveryAllowed()) {
            throw new BusinessException(ErrorCode.PAY_ON_DELIVERY_NOT_ALLOWED, q.partnerName() + " asked for online payment on this quote");
        }
        Map<String, Object> hubAddress = jdbc.sql("SELECT address_snapshot::text AS a FROM service_requests WHERE id = :r").param("r", requestId)
                .query(String.class).optional().map(json::map).orElse(Map.of());
        String fulfilment = fulfilmentChoice == null || fulfilmentChoice.isBlank()
                ? (hubAddress.isEmpty() ? "PICKUP" : "VENDOR_DELIVERY") : fulfilment(fulfilmentChoice);
        if ("VENDOR_DELIVERY".equals(fulfilment) && hubAddress.isEmpty()) throw BusinessException.invalid("Add a delivery address to this request, or choose pickup");

        record QI(UUID requestItemId, String lineKey, String name, String brand, String specs, int quantity, String unit, boolean available,
                  BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal taxRate, BigDecimal taxAmount, BigDecimal lineTotal, Integer moq,
                  Integer leadTimeDays, String note, UUID productId) {}
        List<QI> items = jdbc.sql("""
                SELECT qi.request_item_id, qi.line_key, qi.name, qi.brand, qi.specs, qi.quantity, qi.unit, qi.available, qi.unit_price, qi.discount_amount,
                       qi.tax_rate, qi.tax_amount, qi.line_total, qi.moq, qi.lead_time_days, qi.note, ri.product_id
                FROM quotation_items qi LEFT JOIN request_items ri ON ri.id = qi.request_item_id
                WHERE qi.quotation_id = :q ORDER BY ri.line_no NULLS LAST, qi.line_key""").param("q", quotationId).query(QI.class).list();
        List<QI> available = items.stream().filter(QI::available).toList();
        if (available.isEmpty()) throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "This quotation has no available items");

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        List<OrderLine> lines = new ArrayList<>();
        List<Map<String, Object>> lineJson = new ArrayList<>();
        for (QI i : available) {
            BigDecimal lineGross = i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())).setScale(2, RoundingMode.HALF_UP);
            gross = gross.add(lineGross);
            discount = discount.add(i.discountAmount());
            tax = tax.add(i.taxAmount());
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("quotation", q.quotationNumber());
            snap.put("moq", i.moq());
            snap.put("lead_time_days", i.leadTimeDays());
            snap.put("note", i.note());
            lines.add(new OrderLine(null, i.productId(), null, i.requestItemId(), i.name(), null, i.brand(), i.specs(), i.unit(), i.quantity(),
                    i.unitPrice(), i.discountAmount(), i.taxRate(), i.taxAmount(), i.lineTotal(), snap));
            lineJson.add(Map.of("key", i.lineKey(), "name", i.name(), "quantity", i.quantity(), "line_total", i.lineTotal()));
        }
        BigDecimal deliveryFee = "VENDOR_DELIVERY".equals(fulfilment) && q.deliveryFee() != null ? q.deliveryFee() : BigDecimal.ZERO.setScale(2);
        BigDecimal total = gross.subtract(discount).add(tax).add(deliveryFee).setScale(2, RoundingMode.HALF_UP);

        // One accepted vendor per RFQ: this quote is accepted, every other open quote is closed.
        jdbc.sql("UPDATE quotations SET status = 'ACCEPTED', decided_at = now() WHERE id = :q").param("q", quotationId).update();
        List<UUID> losers = jdbc.sql("""
                UPDATE quotations SET status = 'REJECTED', decided_at = now(), agent_note = COALESCE(agent_note, 'Customer chose another shop')
                WHERE request_id = :r AND id <> :q AND status IN ('SUBMITTED','SHARED','REVISION_REQUESTED') RETURNING partner_id""")
                .param("r", requestId).param("q", quotationId).query(UUID.class).list();
        jdbc.sql("UPDATE partner_invitations SET status = 'CLOSED', closed_at = now() WHERE request_id = :r AND status IN ('INVITED','QUOTED')")
                .param("r", requestId).update();

        UUID locationId = jdbc.sql("""
                SELECT id FROM partner_locations WHERE partner_id = :p AND active ORDER BY is_primary DESC, created_at LIMIT 1""")
                .param("p", q.partnerId()).query(UUID.class).optional().orElse(null);
        Map<String, Object> fulfilmentSnap = new LinkedHashMap<>();
        fulfilmentSnap.put("method", fulfilment);
        if ("VENDOR_DELIVERY".equals(fulfilment)) {
            fulfilmentSnap.put("delivery_address", hubAddress);
            fulfilmentSnap.put("delivery_fee", deliveryFee);
        } else {
            fulfilmentSnap.put("pickup_location", locationSnapshot(locationId));
        }
        fulfilmentSnap.put("quote_terms", q.terms());
        fulfilmentSnap.put("quote_exclusions", q.exclusions());
        fulfilmentSnap.put("lead_time_days", q.leadTimeDays());
        String status = "ONLINE".equals(method) ? OrderStatus.PAYMENT_PENDING.name() : OrderStatus.PLACED.name();
        UUID orderId = insertOrder(requestId, customer, q.partnerId(), locationId, "RFQ", quotationId, status, fulfilment, method, gross, discount, tax,
                false, deliveryFee, total, vendorSnapshot(q.partnerId(), locationId), fulfilmentSnap, null, "rfq:" + key, lines);
        String number = orderNumber(orderId);
        // The request switches to the order lifecycle; its timeline continues.
        jdbc.sql("UPDATE service_requests SET lifecycle = 'ORDER', estimated_total = :t WHERE id = :r").param("t", total).param("r", requestId).update();
        requests.mirror(requestId, r.status(), status, customer, "CUSTOMER", "Customer accepted the quote from " + q.partnerName() + " ("
                + q.quotationNumber() + ") - order " + number + ", Rs " + total.toPlainString() + ", " + available.size() + " item(s), "
                + ("PICKUP".equals(fulfilment) ? "pickup" : "delivery") + ", " + ("ONLINE".equals(method) ? "pay online" : "pay later"), false);
        PaymentService.PaymentView payment = "ONLINE".equals(method)
                ? payments.createOrderPayment(orderId, requestId, customer, total, "rfq-order:" + customer + ":" + key)
                : payments.recordCollectOnDelivery(orderId, requestId, customer, total, "rfq-order:" + customer + ":" + key);
        for (UUID loser : losers.stream().distinct().toList()) {
            notifications.notifyUser(partnerUser(loser), "Quote not selected - " + r.requestNumber(),
                    "The customer chose another shop this time. Thank you for quoting.", "PARTNER_LEAD", "REQUEST", requestId);
        }
        notifications.notifyUser(partnerUser(q.partnerId()), "Your quote was accepted - " + number,
                "ONLINE".equals(method) ? "The customer is paying online; you'll be told when the payment is confirmed."
                        : "Confirm the order in the app and prepare the items.", "PARTNER_ORDER", "ORDER", orderId);
        afterPlaced(orderId);
        audit.record(customer, "CUSTOMER", "RFQ_QUOTE_ACCEPTED", "ORDER", orderId, Map.of("order", number, "quotation", q.quotationNumber(),
                "total", total.toPlainString()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", customerOrder(orderId, customer));
        out.put("payment", payment);
        out.put("duplicate", false);
        return out;
    }

    // ===== 3. Payment, cancellation =======================================================================

    /** Verified gateway webhook result for an order payment (same transaction as the webhook). */
    @EventListener
    public void onPayment(PaymentService.OrderPaymentEvent e) {
        Ord o = lock(e.orderId());
        if (e.succeeded()) {
            if (o.statusEnum() == OrderStatus.PAYMENT_PENDING) {
                move(o, OrderStatus.PAID, null, "SYSTEM", "Payment " + e.paymentNumber() + " confirmed by the gateway", "");
                notifications.notifyUser(partnerUser(o.partnerId()), "New paid order " + o.orderNumber(), "Payment received. Confirm the order and prepare the items.",
                        "PARTNER_ORDER", "ORDER", o.id());
                notifications.notifyUser(o.customerUserId(), "Payment received - " + o.orderNumber(), "We'll let you know when the shop confirms.",
                        "ORDER_UPDATE", "ORDER", o.id());
            } else {
                // Paid after the order moved on (e.g. cancelled): refund, never silently kept.
                ops.raise("PAYMENT_AFTER_CLOSE", "HIGH", "Payment received for order " + o.orderNumber() + " in status " + o.status(), "PAYMENT",
                        e.paymentId(), "Review and refund", "late-order-payment:" + e.paymentId(), Map.of("order", o.orderNumber()));
                if (o.statusEnum().terminal()) payments.closeOrderPayments(o.id(), "Paid after the order was " + o.status().toLowerCase(), null);
            }
        } else {
            requests.note(o.requestId(), o.status(), null, "SYSTEM", "Payment " + e.paymentNumber() + " failed (" + e.failureReason()
                    + ") - the order is kept; the customer can try again");
        }
    }

    /** New online payment attempt for an order still waiting for payment. */
    @Transactional
    public PaymentService.PaymentView pay(UUID orderId, UUID customer, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        Ord o = lock(orderId);
        if (!o.customerUserId().equals(customer)) throw BusinessException.notFound("Order");
        if (o.statusEnum() != OrderStatus.PAYMENT_PENDING) throw BusinessException.transition(o.status(), "PAID");
        Integer paid = jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :o AND status = 'SUCCEEDED'").param("o", orderId).query(Integer.class).single();
        if (paid > 0) throw new BusinessException(ErrorCode.DUPLICATE, "This order is already paid");
        jdbc.sql("UPDATE payments SET status = 'FAILED', failure_reason = COALESCE(failure_reason, 'Replaced by a new attempt'), updated_at = now() WHERE order_id = :o AND status = 'CREATED'")
                .param("o", orderId).update();
        return payments.createOrderPayment(orderId, o.requestId(), customer, o.totalAmount(), "order-retry:" + customer + ":" + key);
    }

    @Transactional
    public Map<String, Object> customerCancel(UUID orderId, UUID customer, String reason) {
        Ord o = lock(orderId);
        if (!o.customerUserId().equals(customer)) throw BusinessException.notFound("Order");
        if (!o.statusEnum().customerCancellable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "The shop has already confirmed this order - contact the shop or BUZZER support",
                    Map.of("from", o.status(), "to", "CANCELLED"));
        }
        String why = reason == null || reason.isBlank() ? "Cancelled by customer" : reason.trim();
        if (why.length() > 300) throw BusinessException.invalid("Reason must be 300 characters or less");
        UUID refund = payments.closeOrderPayments(orderId, why, customer);
        move(o, OrderStatus.CANCELLED, customer, "CUSTOMER", why + (refund == null ? "" : " - refund requested"), ", cancelled_at = now(), cancel_reason = :why", why);
        if (o.statusEnum() != OrderStatus.PAYMENT_PENDING) {
            notifications.notifyUser(partnerUser(o.partnerId()), "Order cancelled - " + o.orderNumber(), why, "PARTNER_ORDER", "ORDER", o.id());
        }
        return customerOrder(orderId, customer);
    }

    // ===== 4. Shop actions ================================================================================

    @Transactional
    public Map<String, Object> confirm(UUID orderId, UUID partnerId, UUID actor) {
        Ord o = lockFor(orderId, partnerId);
        if (!OrderStatus.SHOP_NEW.contains(o.statusEnum())) throw BusinessException.transition(o.status(), "VENDOR_CONFIRMED");
        move(o, OrderStatus.VENDOR_CONFIRMED, actor, "PARTNER", "Shop confirmed the order and the items' availability", ", vendor_confirmed_at = now()");
        notifications.notifyUser(o.customerUserId(), "Order confirmed - " + o.orderNumber(), "PICKUP".equals(o.fulfilment())
                ? "The shop is preparing your order. We'll tell you when it's ready for pickup." : "The shop is preparing your order for delivery.",
                "ORDER_UPDATE", "ORDER", o.id());
        return partnerOrder(orderId, partnerId);
    }

    @Transactional
    public Map<String, Object> reject(UUID orderId, UUID partnerId, UUID actor, String reason) {
        Ord o = lockFor(orderId, partnerId);
        if (reason == null || reason.trim().length() < 3) throw BusinessException.invalid("Tell the customer why (e.g. out of stock)");
        if (reason.length() > 300) throw BusinessException.invalid("Reason must be 300 characters or less");
        if (!o.statusEnum().canMoveTo(OrderStatus.VENDOR_REJECTED)) throw BusinessException.transition(o.status(), "VENDOR_REJECTED");
        UUID refund = payments.closeOrderPayments(orderId, "Shop declined: " + reason.trim(), actor);
        move(o, OrderStatus.VENDOR_REJECTED, actor, "PARTNER", "Shop declined the order: " + reason.trim() + (refund == null ? "" : " - refund requested"),
                ", cancelled_at = now(), cancel_reason = :why", reason.trim());
        notifications.notifyUser(o.customerUserId(), "Order declined - " + o.orderNumber(), reason.trim()
                + (refund == null ? "" : ". Your payment will be refunded."), "ORDER_UPDATE", "ORDER", o.id());
        return partnerOrder(orderId, partnerId);
    }

    @Transactional
    public Map<String, Object> ready(UUID orderId, UUID partnerId, UUID actor) {
        Ord o = lockFor(orderId, partnerId);
        if (!"PICKUP".equals(o.fulfilment())) throw BusinessException.invalid("This order is for delivery - mark it out for delivery");
        if (o.statusEnum() != OrderStatus.VENDOR_CONFIRMED) throw BusinessException.transition(o.status(), "READY_FOR_PICKUP");
        move(o, OrderStatus.READY_FOR_PICKUP, actor, "PARTNER", "Ready for pickup", ", ready_at = now()");
        notifications.notifyUser(o.customerUserId(), "Ready for pickup - " + o.orderNumber(), "Show your pickup code " + o.completionCode() + " at the shop.",
                "ORDER_UPDATE", "ORDER", o.id());
        return partnerOrder(orderId, partnerId);
    }

    @Transactional
    public Map<String, Object> dispatch(UUID orderId, UUID partnerId, UUID actor) {
        Ord o = lockFor(orderId, partnerId);
        if (!"VENDOR_DELIVERY".equals(o.fulfilment())) throw BusinessException.invalid("This order is for pickup - mark it ready for pickup");
        if (o.statusEnum() != OrderStatus.VENDOR_CONFIRMED) throw BusinessException.transition(o.status(), "OUT_FOR_DELIVERY");
        move(o, OrderStatus.OUT_FOR_DELIVERY, actor, "PARTNER", "Out for delivery", ", dispatched_at = now()");
        notifications.notifyUser(o.customerUserId(), "Out for delivery - " + o.orderNumber(), "Give the delivery code " + o.completionCode()
                + " when you receive your order.", "ORDER_UPDATE", "ORDER", o.id());
        return partnerOrder(orderId, partnerId);
    }

    /** Hand-over with the customer's code. Wrong codes are counted (kept even though the call fails). */
    @Transactional(noRollbackFor = BusinessException.class)
    public Map<String, Object> complete(UUID orderId, UUID partnerId, UUID actor, String code) {
        Ord o = lockFor(orderId, partnerId);
        if (o.statusEnum() != OrderStatus.READY_FOR_PICKUP && o.statusEnum() != OrderStatus.OUT_FOR_DELIVERY) {
            throw BusinessException.transition(o.status(), "COMPLETED");
        }
        int max = settings.getInt("orders.max_code_attempts", 5);
        if (o.codeAttempts() >= max) {
            throw new BusinessException(ErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many wrong codes - contact BUZZER support to complete this order");
        }
        if (code == null || !java.security.MessageDigest.isEqual(code.trim().getBytes(), o.completionCode().getBytes())) {
            jdbc.sql("UPDATE orders SET code_attempts = code_attempts + 1, updated_at = now() WHERE id = :id").param("id", orderId).update();
            int left = max - o.codeAttempts() - 1;
            if (left <= 0) {
                ops.raise("ORDER_CODE_LOCKED", "HIGH", "Order " + o.orderNumber() + ": too many wrong completion codes", "ORDER", o.id(),
                        "Call the customer and the shop", "order-code:" + o.id(), Map.of());
            }
            throw new BusinessException(ErrorCode.OTP_INVALID, "Wrong code" + (left > 0 ? " - " + left + " attempt(s) left" : ""));
        }
        move(o, OrderStatus.COMPLETED, actor, "PARTNER", "PICKUP".equals(o.fulfilment()) ? "Picked up by the customer (code verified)"
                : "Delivered to the customer (code verified)", ", completed_at = now()");
        if ("PAY_ON_DELIVERY".equals(o.paymentMethod())) payments.markCollected(orderId);
        settle(o, actor);
        notifications.notifyUser(o.customerUserId(), "Order completed - " + o.orderNumber(), "Thank you for shopping local with BUZZER.",
                "ORDER_UPDATE", "ORDER", o.id());
        return partnerOrder(orderId, partnerId);
    }

    // ===== 5. Admin ==========================================================================================

    @Transactional
    public Map<String, Object> adminCancel(UUID orderId, UUID admin, String reason) {
        Ord o = lock(orderId);
        if (reason == null || reason.trim().length() < 3) throw BusinessException.invalid("A reason is required");
        if (!o.statusEnum().canMoveTo(OrderStatus.CANCELLED)) throw BusinessException.transition(o.status(), "CANCELLED");
        UUID refund = payments.closeOrderPayments(orderId, "Cancelled by BUZZER: " + reason.trim(), admin);
        move(o, OrderStatus.CANCELLED, admin, "ADMIN", "Cancelled by BUZZER: " + reason.trim() + (refund == null ? "" : " - refund requested"),
                ", cancelled_at = now(), cancel_reason = :why", reason.trim());
        notifications.notifyUser(o.customerUserId(), "Order cancelled - " + o.orderNumber(), reason.trim(), "ORDER_UPDATE", "ORDER", o.id());
        notifications.notifyUser(partnerUser(o.partnerId()), "Order cancelled - " + o.orderNumber(), reason.trim(), "PARTNER_ORDER", "ORDER", o.id());
        return adminOrder(orderId);
    }

    // ===== Views ==============================================================================================

    private static final String LIST_SELECT = """
            SELECT o.id, o.order_number, o.status, o.source, o.fulfilment, o.payment_method, o.total_amount, o.delivery_fee, o.created_at, o.updated_at,
                   o.request_id, r.request_number, r.request_type, p.id AS partner_id, p.display_name AS shop_name, cu.full_name AS customer_name,
                   (SELECT count(*) FROM order_items oi WHERE oi.order_id = o.id) AS item_count,
                   (SELECT string_agg(oi.quantity || ' x ' || oi.name, ', ' ORDER BY oi.line_no) FROM order_items oi WHERE oi.order_id = o.id) AS summary,
                   (SELECT py.status FROM payments py WHERE py.order_id = o.id ORDER BY py.created_at DESC LIMIT 1) AS payment_status
            FROM orders o JOIN service_requests r ON r.id = o.request_id JOIN partners p ON p.id = o.partner_id JOIN users cu ON cu.id = o.customer_user_id
            """;

    public List<Map<String, Object>> customerOrders(UUID customer) {
        return withLabels(jdbc.sql(LIST_SELECT + " WHERE o.customer_user_id = :c ORDER BY o.created_at DESC LIMIT 200").param("c", customer)
                .query().listOfRows());
    }

    public Map<String, Object> customerOrder(UUID orderId, UUID customer) {
        Map<String, Object> o = detail(orderId);
        if (!customer.equals(o.get("customer_user_id"))) throw BusinessException.notFound("Order");
        OrderStatus s = OrderStatus.valueOf((String) o.get("status"));
        // The completion code is the customer's: shown to them while the order is on its way, never to the shop.
        if (s.terminal()) o.remove("completion_code");
        o.put("can_cancel", s.customerCancellable());
        o.put("can_pay", s == OrderStatus.PAYMENT_PENDING);
        o.remove("code_attempts");
        return o;
    }

    public List<Map<String, Object>> partnerOrders(UUID partnerId, String group) {
        String where = switch (group == null ? "" : group.toUpperCase()) {
            case "NEW" -> " AND o.status IN ('PLACED','PAID')";
            case "ACTIVE" -> " AND o.status IN ('VENDOR_CONFIRMED','READY_FOR_PICKUP','OUT_FOR_DELIVERY')";
            case "DONE" -> " AND o.status IN ('COMPLETED','CANCELLED','VENDOR_REJECTED')";
            default -> " AND o.status <> 'PAYMENT_PENDING'";
        };
        return withLabels(jdbc.sql(LIST_SELECT + " WHERE o.partner_id = :p" + where + " ORDER BY o.created_at DESC LIMIT 200").param("p", partnerId)
                .query().listOfRows());
    }

    /** What the shop sees: never the completion code; customer contact and delivery address once the shop has confirmed. */
    public Map<String, Object> partnerOrder(UUID orderId, UUID partnerId) {
        Map<String, Object> o = detail(orderId);
        if (!partnerId.equals(o.get("partner_id"))) throw BusinessException.notFound("Order");
        OrderStatus s = OrderStatus.valueOf((String) o.get("status"));
        if (s == OrderStatus.PAYMENT_PENDING) throw BusinessException.notFound("Order");
        o.remove("completion_code");
        boolean confirmed = OrderStatus.SHOP_ACTIVE.contains(s) || s == OrderStatus.COMPLETED;
        if (!confirmed) {
            o.remove("customer_phone");
            Object f = o.get("fulfilment_snapshot");
            if (f instanceof Map<?, ?> fm && fm.containsKey("delivery_address")) {
                Map<String, Object> copy = new LinkedHashMap<>();
                fm.forEach((k, v) -> copy.put(String.valueOf(k), v));
                Object a = copy.get("delivery_address");
                if (a instanceof Map<?, ?> am) copy.put("delivery_address", Map.of("locality_name", String.valueOf(am.get("locality_name")),
                        "city_name", String.valueOf(am.get("city_name")), "hidden", "Full address is shown after you confirm"));
                o.put("fulfilment_snapshot", copy);
            }
        }
        o.put("actions", switch (s) {
            case PLACED, PAID -> List.of("CONFIRM", "REJECT");
            case VENDOR_CONFIRMED -> List.of("PICKUP".equals(o.get("fulfilment")) ? "READY" : "DISPATCH", "REJECT");
            case READY_FOR_PICKUP, OUT_FOR_DELIVERY -> List.of("COMPLETE");
            default -> List.of();
        });
        return o;
    }

    public List<Map<String, Object>> adminOrders(String status, String q, UUID partnerId) {
        if (status != null && !status.isBlank()) OrderStatus.valueOf(status.toUpperCase());
        return withLabels(jdbc.sql(LIST_SELECT + """
                WHERE (CAST(:s AS text) IS NULL OR o.status = CAST(:s AS text))
                  AND (CAST(:p AS uuid) IS NULL OR o.partner_id = CAST(:p AS uuid))
                  AND (CAST(:q AS text) IS NULL OR o.order_number ILIKE '%' || CAST(:q AS text) || '%' OR r.request_number ILIKE '%' || CAST(:q AS text) || '%'
                       OR p.display_name ILIKE '%' || CAST(:q AS text) || '%' OR cu.full_name ILIKE '%' || CAST(:q AS text) || '%')
                ORDER BY o.created_at DESC LIMIT 500""").param("s", status == null || status.isBlank() ? null : status.toUpperCase())
                .param("p", partnerId).param("q", q == null || q.isBlank() ? null : q.trim()).query().listOfRows());
    }

    public Map<String, Object> adminOrder(UUID orderId) {
        Map<String, Object> o = detail(orderId);
        o.remove("completion_code");
        return o;
    }

    public Map<String, Object> summaryForRequest(UUID requestId) {
        return jdbc.sql("SELECT id, order_number, status, total_amount FROM orders WHERE request_id = :r").param("r", requestId).query().listOfRows()
                .stream().findFirst().map(m -> {
                    Map<String, Object> out = new LinkedHashMap<>(m);
                    out.put("status_label", OrderStatus.valueOf((String) m.get("status")).label());
                    return out;
                }).orElse(null);
    }

    private Map<String, Object> detail(UUID orderId) {
        Map<String, Object> o = jdbc.sql("""
                SELECT o.id, o.order_number, o.request_id, r.request_number, r.request_type, o.customer_user_id, cu.full_name AS customer_name,
                       cu.phone AS customer_phone, o.partner_id, o.location_id, o.source, o.quotation_id, q.quotation_number, o.status, o.fulfilment,
                       o.payment_method, o.items_total, o.discount_total, o.tax_total, o.prices_include_tax, o.delivery_fee, o.total_amount, o.currency,
                       o.vendor_snapshot::text AS vendor_snapshot, o.fulfilment_snapshot::text AS fulfilment_snapshot, o.customer_note,
                       o.completion_code, o.code_attempts, o.vendor_confirmed_at, o.ready_at, o.dispatched_at, o.completed_at, o.cancelled_at,
                       o.cancel_reason, o.created_at, o.updated_at
                FROM orders o JOIN service_requests r ON r.id = o.request_id JOIN users cu ON cu.id = o.customer_user_id
                LEFT JOIN quotations q ON q.id = o.quotation_id WHERE o.id = :id""").param("id", orderId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("Order"));
        Map<String, Object> out = new LinkedHashMap<>(o);
        out.put("status_label", OrderStatus.valueOf((String) o.get("status")).label());
        out.put("vendor_snapshot", json.map((String) o.get("vendor_snapshot")));
        out.put("fulfilment_snapshot", json.map((String) o.get("fulfilment_snapshot")));
        out.put("items", jdbc.sql("""
                SELECT line_no, name, variant_name, brand, specs, unit, quantity, unit_price, discount_amount, tax_rate, tax_amount, line_total,
                       listing_id, product_id FROM order_items WHERE order_id = :o ORDER BY line_no""").param("o", orderId).query().listOfRows());
        out.put("payments", payments.forOrder(orderId));
        out.put("timeline", requests.history((UUID) o.get("request_id")));
        return out;
    }

    private List<Map<String, Object>> withLabels(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("status_label", OrderStatus.valueOf((String) r.get("status")).label());
            return m;
        }).toList();
    }

    // ===== helpers ============================================================================================

    private record OrderLine(UUID listingId, UUID productId, UUID variantId, UUID requestItemId, String name, String variantName, String brand,
                             String specs, String unit, int quantity, BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal taxRate,
                             BigDecimal taxAmount, BigDecimal lineTotal, Map<String, Object> snapshot) {}

    private UUID insertOrder(UUID requestId, UUID customer, UUID partnerId, UUID locationId, String source, UUID quotationId, String status,
                             String fulfilment, String method, BigDecimal itemsTotal, BigDecimal discount, BigDecimal tax, boolean inclusive,
                             BigDecimal deliveryFee, BigDecimal total, Map<String, Object> vendor, Map<String, Object> fulfilmentSnap, String note,
                             String key, List<OrderLine> lines) {
        String code = String.format("%04d", RANDOM.nextInt(10000));
        UUID id = jdbc.sql("""
                INSERT INTO orders(order_number, request_id, customer_user_id, partner_id, location_id, source, quotation_id, status, fulfilment,
                  payment_method, items_total, discount_total, tax_total, prices_include_tax, delivery_fee, total_amount, vendor_snapshot,
                  fulfilment_snapshot, customer_note, completion_code, idempotency_key)
                VALUES (:n, :r, :c, :p, :loc, :src, :q, :st, :f, :m, :items, :disc, :tax, :incl, :del, :total, CAST(:vendor AS jsonb),
                  CAST(:ful AS jsonb), :note, :code, :key) RETURNING id""")
                .param("n", ids.next(BusinessIds.Kind.ORDER)).param("r", requestId).param("c", customer).param("p", partnerId).param("loc", locationId)
                .param("src", source).param("q", quotationId).param("st", status).param("f", fulfilment).param("m", method)
                .param("items", itemsTotal.setScale(2, RoundingMode.HALF_UP)).param("disc", discount.setScale(2, RoundingMode.HALF_UP))
                .param("tax", tax.setScale(2, RoundingMode.HALF_UP)).param("incl", inclusive).param("del", deliveryFee).param("total", total)
                .param("vendor", json.write(vendor)).param("ful", json.write(fulfilmentSnap)).param("note", note).param("code", code).param("key", key)
                .query(UUID.class).single();
        int no = 0;
        for (OrderLine l : lines) {
            jdbc.sql("""
                    INSERT INTO order_items(order_id, line_no, listing_id, product_id, variant_id, name, variant_name, brand, specs, unit, quantity,
                      unit_price, discount_amount, tax_rate, tax_amount, line_total, snapshot)
                    VALUES (:o, :no, :l, :pr, :v, :n, :vn, :b, :sp, :u, :q, :up, :d, :tr, :ta, :lt, CAST(:snap AS jsonb))""")
                    .param("o", id).param("no", ++no).param("l", l.listingId()).param("pr", l.productId()).param("v", l.variantId()).param("n", l.name())
                    .param("vn", l.variantName()).param("b", l.brand()).param("sp", l.specs()).param("u", l.unit()).param("q", l.quantity())
                    .param("up", l.unitPrice()).param("d", l.discountAmount()).param("tr", l.taxRate() == null ? BigDecimal.ZERO : l.taxRate())
                    .param("ta", l.taxAmount()).param("lt", l.lineTotal()).param("snap", json.write(l.snapshot())).update();
        }
        return id;
    }

    /** Request-hub row for a direct purchase (shared request number, timeline and "My Requests" tracking). */
    private UUID createHub(UUID customer, UUID categoryId, UUID subcategoryId, UUID cityId, UUID localityId, UUID addressId, Map<String, Object> address,
                           String description, String status, List<Map<String, Object>> lineItems, BigDecimal total, String key) {
        UUID agentId = allocation.activeLink(customer, "PRIMARY").orElse(null);
        return jdbc.sql("""
                INSERT INTO service_requests(request_number, customer_user_id, created_by_user_id, created_via, customer_confirmed_at, category_id,
                  subcategory_id, city_id, locality_id, address_id, address_snapshot, answers, description, priority, status, agent_id,
                  idempotency_key, booking_mode, line_items, estimated_total, has_quote_items, agent_source, handling_mode, request_type, vertical,
                  lifecycle, agent_seen_at)
                VALUES (:num, :c, :c, 'CUSTOMER_APP', now(), :cat, :sub, :city, :loc, :addr, CAST(:snap AS jsonb), '{}'::jsonb, :d, 'NORMAL', :st,
                  :agent, :key, 'SELF', CAST(:lines AS jsonb), :total, FALSE, :src, 'AUTOMATED', 'PRODUCT_PURCHASE', 'MARKETPLACE', 'ORDER', now())
                RETURNING id""")
                .param("num", ids.next(BusinessIds.Kind.REQUEST)).param("c", customer).param("cat", categoryId).param("sub", subcategoryId)
                .param("city", cityId).param("loc", localityId).param("addr", addressId).param("snap", json.write(address)).param("d", description)
                .param("st", status).param("agent", agentId).param("key", key).param("lines", json.write(lineItems)).param("total", total)
                .param("src", agentId == null ? "NONE" : "DEDICATED").query(UUID.class).single();
    }

    private void history(UUID requestId, String from, String to, UUID actor, String role, String reason) {
        jdbc.sql("""
                INSERT INTO request_status_history(request_id, from_status, to_status, actor_user_id, actor_role, reason) VALUES (:r, :f, :t, :a, :role, :why)""")
                .param("r", requestId).param("f", from).param("t", to).param("a", actor).param("role", role).param("why", reason).update();
    }

    /** Validated order transition, mirrored on the request hub and its timeline. Caller holds both locks (see {@link #lock}). */
    private void move(Ord o, OrderStatus to, UUID actor, String role, String reason, String extraSet) {
        move(o, to, actor, role, reason, extraSet, null);
    }

    private void move(Ord o, OrderStatus to, UUID actor, String role, String reason, String extraSet, String why) {
        OrderStatus from = o.statusEnum();
        if (!from.canMoveTo(to)) throw BusinessException.transition(from.name(), to.name());
        var stmt = jdbc.sql("UPDATE orders SET status = :to, version = version + 1, updated_at = now()" + extraSet + " WHERE id = :id")
                .param("to", to.name()).param("id", o.id());
        if (extraSet.contains(":why")) stmt = stmt.param("why", why);
        stmt.update();
        requests.mirror(o.requestId(), from.name(), to.name(), actor, role, reason, to.terminal());
        audit.record(actor, role, "ORDER_STATUS_CHANGED", "ORDER", o.id(), Map.of("from", from.name(), "to", to.name(), "order", o.orderNumber()));
    }

    /** Lock order: request hub row first (same as payments), then the order. */
    private Ord lock(UUID orderId) {
        UUID requestId = jdbc.sql("SELECT request_id FROM orders WHERE id = :id").param("id", orderId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Order"));
        requests.lock(requestId);
        return jdbc.sql("SELECT " + ORD_COLS + " FROM orders WHERE id = :id FOR UPDATE").param("id", orderId).query(Ord.class).single();
    }

    private Ord lockFor(UUID orderId, UUID partnerId) {
        Ord o = lock(orderId);
        if (!o.partnerId().equals(partnerId) || o.statusEnum() == OrderStatus.PAYMENT_PENDING) throw BusinessException.notFound("Order");
        return o;
    }

    private void afterPlaced(UUID orderId) {
        Ord o = jdbc.sql("SELECT " + ORD_COLS + " FROM orders WHERE id = :id").param("id", orderId).query(Ord.class).single();
        if (o.statusEnum() == OrderStatus.PLACED) {
            notifications.notifyUser(partnerUser(o.partnerId()), "New order " + o.orderNumber(), "Rs " + o.totalAmount().toPlainString()
                    + " - pay at " + ("PICKUP".equals(o.fulfilment()) ? "pickup" : "delivery") + ". Confirm it in the app.", "PARTNER_ORDER", "ORDER", o.id());
        }
        notifications.notifyUser(o.customerUserId(), "Order placed - " + o.orderNumber(), o.statusEnum() == OrderStatus.PAYMENT_PENDING
                ? "Complete the payment to send it to the shop." : "The shop will confirm it shortly.", "ORDER_UPDATE", "ORDER", o.id());
    }

    /** Commission, tax invoice, ledger and payout on completion (same rules as service jobs). */
    private void settle(Ord o, UUID actor) {
        record Ctx(UUID subcategoryId, UUID categoryId, UUID cityId) {}
        Ctx c = jdbc.sql("SELECT subcategory_id, category_id, city_id FROM service_requests WHERE id = :r").param("r", o.requestId()).query(Ctx.class).single();
        CommissionService.Resolved rule = commissions.resolve(o.partnerId(), null, c.subcategoryId(), c.categoryId(), c.cityId());
        BigDecimal base = o.totalAmount().subtract(o.deliveryFee());
        BigDecimal commission = CommissionService.commissionOn(base, rule.ratePercent());
        BigDecimal earning = o.totalAmount().subtract(commission);
        String memo = "Commission " + rule.ratePercent().toPlainString() + "% (" + rule.scopeType() + " rule) on order " + o.orderNumber();
        List<Map<String, Object>> invoiceLines = jdbc.sql("""
                SELECT name AS description, quantity, unit_price, tax_rate, tax_amount, line_total AS amount FROM order_items WHERE order_id = :o ORDER BY line_no""")
                .param("o", o.id()).query().listOfRows();
        record T(BigDecimal tax, BigDecimal discount) {}
        T t = jdbc.sql("SELECT tax_total AS tax, discount_total AS discount FROM orders WHERE id = :o").param("o", o.id()).query(T.class).single();
        jdbc.sql("""
                INSERT INTO invoices(invoice_number, invoice_type, request_id, customer_user_id, line_items, subtotal, tax_amount, discount_amount, total_amount)
                VALUES (:n, 'TAX_INVOICE', :r, :c, CAST(:items AS jsonb), :sub, :tax, :disc, :total)""")
                .param("n", ids.next(BusinessIds.Kind.INVOICE)).param("r", o.requestId()).param("c", o.customerUserId())
                .param("items", json.write(invoiceLines)).param("sub", o.totalAmount().subtract(t.tax()).add(t.discount())).param("tax", t.tax())
                .param("disc", t.discount()).param("total", o.totalAmount()).update();
        boolean cash = "PAY_ON_DELIVERY".equals(o.paymentMethod());
        List<LedgerService.Entry> entries = new ArrayList<>();
        if (cash) entries.add(new LedgerService.Entry("CASH_COLLECTED_BY_PARTNER", "DEBIT", o.totalAmount(), o.partnerId(), "Cash collected for " + o.orderNumber()));
        entries.add(new LedgerService.Entry("PARTNER_EARNING", "CREDIT", earning, o.partnerId(), "Earning on order " + o.orderNumber()));
        entries.add(new LedgerService.Entry("PLATFORM_COMMISSION", "CREDIT", commission, o.partnerId(), memo));
        ledger.post(o.requestId(), "ORDER", o.id(), entries);
        if (!cash) {
            jdbc.sql("""
                    INSERT INTO partner_payouts(payout_number, partner_id, request_id, amount, status, due_on) VALUES (:n, :p, :r, :a, 'PENDING', :due)""")
                    .param("n", ids.next(BusinessIds.Kind.PAYOUT)).param("p", o.partnerId()).param("r", o.requestId()).param("a", earning)
                    .param("due", LocalDate.now().plusDays(settings.getInt("settlement.target_days", 1))).update();
        }
        requests.note(o.requestId(), OrderStatus.COMPLETED.name(), actor, "SYSTEM", "Commission Rs " + commission.toPlainString() + "; shop earning Rs "
                + earning.toPlainString() + (cash ? " (cash collected by the shop)" : " - payout scheduled"));
        notifications.notifyUser(partnerUser(o.partnerId()), "Order completed - " + o.orderNumber(), "Earning Rs " + earning.toPlainString()
                + " after commission Rs " + commission.toPlainString() + (cash ? " (cash collected)" : " - payout scheduled"), "PARTNER_EARNING", "ORDER", o.id());
    }

    private Map<String, Object> vendorSnapshot(UUID partnerId, UUID locationId) {
        Map<String, Object> v = new LinkedHashMap<>(jdbc.sql("""
                SELECT p.id AS partner_id, p.display_name AS name, p.business_name, p.contact_phone AS phone FROM partners p WHERE p.id = :p""")
                .param("p", partnerId).query().singleRow());
        v.put("location", locationSnapshot(locationId));
        return v;
    }

    private Map<String, Object> locationSnapshot(UUID locationId) {
        if (locationId == null) return null;
        return jdbc.sql("""
                SELECT pl.id, pl.name, pl.address, l.name AS locality_name, pl.opening_hours, pl.phone FROM partner_locations pl
                LEFT JOIN localities l ON l.id = pl.locality_id WHERE pl.id = :id""").param("id", locationId).query().listOfRows().stream().findFirst().orElse(null);
    }

    private Map<String, Object> address(UUID addressId, UUID customer) {
        return jdbc.sql("""
                SELECT a.id, a.label, a.line1, a.line2, a.landmark, a.pincode, a.city_id, a.locality_id, a.latitude, a.longitude,
                       c.name AS city_name, l.name AS locality_name
                FROM addresses a JOIN cities c ON c.id = a.city_id LEFT JOIN localities l ON l.id = a.locality_id
                WHERE a.id = :a AND a.user_id = :u AND a.deleted_at IS NULL""").param("a", addressId).param("u", customer).query().listOfRows()
                .stream().findFirst().map(LinkedHashMap::new).orElseThrow(() -> BusinessException.invalid("Address not found"));
    }

    private UUID existing(UUID customer, String key) {
        return jdbc.sql("SELECT id FROM orders WHERE customer_user_id = :c AND idempotency_key = :k").param("c", customer).param("k", key)
                .query(UUID.class).optional().orElse(null);
    }

    private Map<String, Object> withPayment(UUID orderId, UUID customer, boolean duplicate) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> order = customerOrder(orderId, customer);
        out.put("order", order);
        List<?> pays = (List<?>) order.get("payments");
        out.put("payment", pays.isEmpty() ? null : pays.getFirst());
        out.put("duplicate", duplicate);
        return out;
    }

    private String orderNumber(UUID orderId) {
        return jdbc.sql("SELECT order_number FROM orders WHERE id = :id").param("id", orderId).query(String.class).single();
    }

    private UUID partnerUser(UUID partnerId) {
        return jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", partnerId).query(UUID.class).single();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 80) throw BusinessException.invalid("Idempotency-Key header is required");
        return key.trim();
    }

    private static String fulfilment(String f) {
        String v = f == null ? "PICKUP" : f.trim().toUpperCase();
        if ("DELIVERY".equals(v)) v = "VENDOR_DELIVERY";
        if (!Set.of("PICKUP", "VENDOR_DELIVERY").contains(v)) throw BusinessException.invalid("Choose pickup or delivery");
        return v;
    }

    private String paymentMethod(String m) {
        String v = m == null ? "" : m.trim().toUpperCase();
        if (!Set.of("ONLINE", "PAY_ON_DELIVERY").contains(v)) throw BusinessException.invalid("Choose to pay online or at pickup / on delivery");
        if ("PAY_ON_DELIVERY".equals(v) && !settings.getBoolean("orders.pay_on_delivery_enabled", true)) {
            throw new BusinessException(ErrorCode.PAY_ON_DELIVERY_NOT_ALLOWED, "Pay at pickup / on delivery is switched off - please pay online");
        }
        return v;
    }
}
