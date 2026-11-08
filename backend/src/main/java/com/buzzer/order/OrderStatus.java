package com.buzzer.order;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Marketplace order lifecycle (lifecycle ORDER on the request hub). Separate from the service-request lifecycle.
 * <pre>
 *   PAYMENT_PENDING -> PAID -> VENDOR_CONFIRMED -> READY_FOR_PICKUP  -> COMPLETED (pickup code)
 *   PLACED (pay later) -----^                   -> OUT_FOR_DELIVERY -> COMPLETED (delivery code)
 *   before hand-over: CANCELLED (customer before the shop confirms; admin anytime) / VENDOR_REJECTED (shop)
 * </pre>
 */
public enum OrderStatus {
    PAYMENT_PENDING("Waiting for payment"),
    PLACED("Waiting for the shop to confirm"),
    PAID("Paid - waiting for the shop to confirm"),
    VENDOR_CONFIRMED("Confirmed by the shop"),
    READY_FOR_PICKUP("Ready for pickup"),
    OUT_FOR_DELIVERY("Out for delivery"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    VENDOR_REJECTED("Declined by the shop");

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(PAYMENT_PENDING, EnumSet.of(PAID, CANCELLED));
        ALLOWED.put(PLACED, EnumSet.of(VENDOR_CONFIRMED, VENDOR_REJECTED, CANCELLED));
        ALLOWED.put(PAID, EnumSet.of(VENDOR_CONFIRMED, VENDOR_REJECTED, CANCELLED));
        ALLOWED.put(VENDOR_CONFIRMED, EnumSet.of(READY_FOR_PICKUP, OUT_FOR_DELIVERY, VENDOR_REJECTED, CANCELLED));
        ALLOWED.put(READY_FOR_PICKUP, EnumSet.of(COMPLETED, CANCELLED));
        ALLOWED.put(OUT_FOR_DELIVERY, EnumSet.of(COMPLETED, CANCELLED));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(VENDOR_REJECTED, EnumSet.noneOf(OrderStatus.class));
    }

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean canMoveTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean terminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** The customer may cancel until the shop has confirmed the order. */
    public boolean customerCancellable() {
        return this == PAYMENT_PENDING || this == PLACED || this == PAID;
    }

    /** Statuses in which the shop still has to act. */
    public static final Set<OrderStatus> SHOP_NEW = EnumSet.of(PLACED, PAID);
    public static final Set<OrderStatus> SHOP_ACTIVE = EnumSet.of(VENDOR_CONFIRMED, READY_FOR_PICKUP, OUT_FOR_DELIVERY);

    public static Map<OrderStatus, Set<OrderStatus>> transitions() {
        return ALLOWED;
    }
}
