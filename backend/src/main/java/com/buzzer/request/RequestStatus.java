package com.buzzer.request;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Service request lifecycle (state machine). Every transition is validated here and recorded in
 * request_status_history with actor, timestamp and reason.
 */
public enum RequestStatus {
    REQUEST_CREATED, AGENT_REVIEW, PARTNER_QUOTATION, CUSTOMER_APPROVAL, PAYMENT_PENDING, PAYMENT_CONFIRMED,
    PARTNER_ASSIGNED, PARTNER_CONFIRMED, RESCHEDULING_REQUESTED, PARTNER_ARRIVED, SERVICE_STARTED, IN_PROGRESS,
    COMPLETION_PENDING, COMPLETED, SETTLEMENT_PENDING, SETTLED,
    CANCELLED, REJECTED, EXPIRED, PARTNER_DECLINED, NO_SHOW, DISPUTED, REFUND_PENDING, REFUNDED;

    private static final Map<RequestStatus, Set<RequestStatus>> ALLOWED = new EnumMap<>(RequestStatus.class);

    static {
        allow(REQUEST_CREATED, AGENT_REVIEW, CANCELLED, REJECTED);
        allow(AGENT_REVIEW, PARTNER_QUOTATION, CANCELLED, REJECTED);
        allow(PARTNER_QUOTATION, CUSTOMER_APPROVAL, AGENT_REVIEW, CANCELLED, EXPIRED);
        allow(CUSTOMER_APPROVAL, PAYMENT_PENDING, PARTNER_ASSIGNED, PARTNER_QUOTATION, CANCELLED, EXPIRED);
        allow(PAYMENT_PENDING, PAYMENT_CONFIRMED, CUSTOMER_APPROVAL, CANCELLED);
        allow(PAYMENT_CONFIRMED, PARTNER_ASSIGNED, CANCELLED, REFUND_PENDING);
        allow(PARTNER_ASSIGNED, PARTNER_CONFIRMED, PARTNER_DECLINED, CANCELLED);
        allow(PARTNER_DECLINED, PARTNER_ASSIGNED, PARTNER_QUOTATION, CANCELLED, REFUND_PENDING);
        allow(PARTNER_CONFIRMED, PARTNER_ARRIVED, RESCHEDULING_REQUESTED, PARTNER_DECLINED, NO_SHOW, CANCELLED);
        allow(RESCHEDULING_REQUESTED, PARTNER_CONFIRMED, PARTNER_DECLINED, CANCELLED);
        allow(PARTNER_ARRIVED, SERVICE_STARTED, NO_SHOW, CANCELLED);
        allow(SERVICE_STARTED, IN_PROGRESS, COMPLETION_PENDING, DISPUTED);
        allow(IN_PROGRESS, COMPLETION_PENDING, DISPUTED);
        allow(COMPLETION_PENDING, COMPLETED, DISPUTED);
        allow(COMPLETED, SETTLEMENT_PENDING, DISPUTED);
        allow(SETTLEMENT_PENDING, SETTLED, DISPUTED);
        allow(DISPUTED, COMPLETED, REFUND_PENDING, SETTLEMENT_PENDING);
        allow(NO_SHOW, PARTNER_ASSIGNED, PARTNER_QUOTATION, CANCELLED, REFUND_PENDING);
        allow(CANCELLED, REFUND_PENDING);
        allow(REFUND_PENDING, REFUNDED);
        allow(SETTLED);
        allow(REJECTED);
        allow(EXPIRED);
        allow(REFUNDED);
    }

    private static void allow(RequestStatus from, RequestStatus... to) {
        ALLOWED.put(from, to.length == 0 ? EnumSet.noneOf(RequestStatus.class) : EnumSet.of(to[0], to));
    }

    public boolean canMoveTo(RequestStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    public static Map<RequestStatus, Set<RequestStatus>> transitions() {
        return ALLOWED;
    }

    /** Statuses in which the customer may still cancel (before the service has started). */
    public boolean customerCancellable() {
        return EnumSet.of(REQUEST_CREATED, AGENT_REVIEW, PARTNER_QUOTATION, CUSTOMER_APPROVAL, PAYMENT_PENDING,
                PAYMENT_CONFIRMED, PARTNER_ASSIGNED, PARTNER_CONFIRMED, RESCHEDULING_REQUESTED, PARTNER_DECLINED).contains(this);
    }

    public boolean isOpen() {
        return !EnumSet.of(SETTLED, CANCELLED, REJECTED, EXPIRED, REFUNDED).contains(this);
    }
}
