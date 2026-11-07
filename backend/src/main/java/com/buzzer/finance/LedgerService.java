package com.buzzer.finance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only financial ledger - the source of truth for money. Balances are always derived from
 * entries, never stored as mutable fields. Posting is idempotent: (reference, account, direction) is unique,
 * so a retried webhook or job can never double-post.
 */
@Service
public class LedgerService {
    public record Entry(String account, String direction, BigDecimal amount, UUID partnerId, String memo) {}

    private final JdbcClient jdbc;

    public LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void post(UUID requestId, String referenceType, UUID referenceId, List<Entry> entries) {
        UUID group = UUID.randomUUID();
        for (Entry e : entries) {
            if (e.amount() == null || e.amount().signum() < 0) throw new IllegalArgumentException("Ledger amounts must be non-negative");
            jdbc.sql("""
                    INSERT INTO ledger_entries(entry_group, account, direction, amount, partner_id, request_id, reference_type, reference_id, memo)
                    VALUES (:g, :acc, :dir, :amt, :p, :r, :rt, :ri, :memo)
                    ON CONFLICT (reference_type, reference_id, account, direction) DO NOTHING""")
                    .param("g", group).param("acc", e.account()).param("dir", e.direction()).param("amt", e.amount())
                    .param("p", e.partnerId()).param("r", requestId).param("rt", referenceType).param("ri", referenceId)
                    .param("memo", e.memo()).update();
        }
    }

    /** Partner position derived purely from ledger entries. */
    public Map<String, Object> partnerSummary(UUID partnerId) {
        return jdbc.sql("""
                SELECT
                  COALESCE(sum(amount) FILTER (WHERE account = 'PARTNER_EARNING' AND direction = 'CREDIT'), 0) AS total_earned,
                  COALESCE(sum(amount) FILTER (WHERE account = 'PARTNER_PAYOUT' AND direction = 'DEBIT'), 0) AS total_paid_out,
                  COALESCE(sum(amount) FILTER (WHERE account = 'CASH_COLLECTED_BY_PARTNER' AND direction = 'DEBIT'), 0) AS cash_collected,
                  COALESCE(sum(amount) FILTER (WHERE account = 'PLATFORM_COMMISSION' AND direction = 'CREDIT'), 0) AS commission_deducted
                FROM ledger_entries WHERE partner_id = :p""").param("p", partnerId).query().singleRow();
    }

    public List<Map<String, Object>> partnerEntries(UUID partnerId, int limit) {
        return jdbc.sql("""
                SELECT le.account, le.direction, le.amount, le.memo, le.created_at, sr.request_number
                FROM ledger_entries le LEFT JOIN service_requests sr ON sr.id = le.request_id
                WHERE le.partner_id = :p ORDER BY le.created_at DESC LIMIT :l""").param("p", partnerId).param("l", limit).query().listOfRows();
    }
}
