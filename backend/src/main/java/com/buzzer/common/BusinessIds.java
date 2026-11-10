package com.buzzer.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Year;

/**
 * Generates permanent, human-readable business identifiers backed by database sequences,
 * e.g. BZ-REQ-2026-00001234. Sequences are never reset, so numbers stay globally unique.
 */
@Component
public class BusinessIds {
    public enum Kind {
        REQUEST("REQ", "seq_request_number"),
        INVITATION("INV", "seq_invitation_number"),
        QUOTATION("QUO", "seq_quotation_number"),
        ASSIGNMENT("ASN", "seq_assignment_number"),
        BOOKING("BKG", "seq_booking_number"),
        INVOICE("BIL", "seq_invoice_number"),
        PAYMENT("PAY", "seq_payment_number"),
        REFUND("RFD", "seq_refund_number"),
        PAYOUT("PYO", "seq_payout_number"),
        ORDER("ORD", "seq_order_number"),
        DRAFT("DRF", "seq_draft_number");

        final String prefix;
        final String sequence;

        Kind(String prefix, String sequence) {
            this.prefix = prefix;
            this.sequence = sequence;
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public BusinessIds(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String next(Kind kind) {
        Long value = jdbc.sql("SELECT nextval('" + kind.sequence + "')").query(Long.class).single();
        return format(kind, Year.now(clock).getValue(), value);
    }

    public static String format(Kind kind, int year, long value) {
        return "BZ-%s-%d-%08d".formatted(kind.prefix, year, value);
    }
}
