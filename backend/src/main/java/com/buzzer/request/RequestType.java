package com.buzzer.request;

import java.util.Set;

/**
 * The five kinds of customer request. All share one identity (request number, customer, category, location, schedule,
 * agent, timeline) in {@code service_requests}; each is driven by its own lifecycle.
 */
public enum RequestType {
    PRODUCT_PURCHASE("Purchase", "ORDER"),
    PRODUCT_RFQ("Quotation request", "REQUEST_V1"),
    SERVICE_BOOKING("Service booking", "REQUEST_V1"),
    TASK_REQUEST("Task / errand", "REQUEST_V1"),
    PERSONAL_ASSISTANCE("Personal assistance", "REQUEST_V1");

    private final String label;
    private final String defaultLifecycle;

    RequestType(String label, String defaultLifecycle) {
        this.label = label;
        this.defaultLifecycle = defaultLifecycle;
    }

    public String label() {
        return label;
    }

    /** State machine for new requests of this type (direct purchases get their own ORDER lifecycle in Stage 5, tasks a TASK lifecycle in Stage 8). */
    public String defaultLifecycle() {
        return defaultLifecycle;
    }

    /** Parses a client-supplied type; unknown values are a 400, not a 500. */
    public static RequestType parse(String value) {
        try {
            return RequestType.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw com.buzzer.common.BusinessException.invalid("Unknown request type: " + value);
        }
    }

    /** Type of a request created through the existing flows, from how it was created and where its category lives. */
    public static RequestType forCreation(String bookingMode, String vertical) {
        if ("AGENT_DIRECT".equals(bookingMode)) return "TASKS".equals(vertical) ? TASK_REQUEST : PERSONAL_ASSISTANCE;
        return switch (vertical == null ? "SERVICES" : vertical) {
            case "MARKETPLACE" -> PRODUCT_RFQ;
            case "TASKS" -> TASK_REQUEST;
            case "ASSISTANCE" -> PERSONAL_ASSISTANCE;
            default -> SERVICE_BOOKING;
        };
    }

    /** Customer-facing groups used by "My Requests" filters, independent of the lifecycle behind them. */
    public static final Set<String> STATUS_GROUPS = Set.of("OPEN", "ACTION_NEEDED", "IN_PROGRESS", "DONE", "CLOSED");

    /** SQL expression (alias r) mapping REQUEST_V1 statuses to a status group. Later lifecycles add their own branches. */
    public static final String STATUS_GROUP_SQL = """
            CASE
              WHEN r.status IN ('REQUEST_CREATED','AGENT_REVIEW','PARTNER_QUOTATION') THEN 'OPEN'
              WHEN r.status IN ('CUSTOMER_APPROVAL','PAYMENT_PENDING','READY_FOR_PICKUP') THEN 'ACTION_NEEDED'
              WHEN r.status IN ('COMPLETED','SETTLEMENT_PENDING','SETTLED') THEN 'DONE'
              WHEN r.status IN ('CANCELLED','REJECTED','EXPIRED','REFUNDED','REFUND_PENDING','VENDOR_REJECTED') THEN 'CLOSED'
              ELSE 'IN_PROGRESS' END""";
}
