package com.buzzer.finance;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import com.buzzer.notification.NotificationService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * On verified completion: tax invoice, commission calculation, ledger postings and a partner payout
 * record targeted at T+N (configurable; actual settlement depends on provider/bank processing,
 * holidays, refunds and reconciliation).
 */
@Service
public class SettlementService {
    private record Job(UUID requestId, UUID customerUserId, UUID partnerId, UUID assignmentId, BigDecimal total, BigDecimal tax,
                       BigDecimal discount, UUID serviceItemId, UUID subcategoryId, UUID categoryId, UUID cityId, String method, String paymentStatus) {}

    private final JdbcClient jdbc;
    private final BusinessIds ids;
    private final CommissionService commissions;
    private final LedgerService ledger;
    private final RequestRepository requests;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AuditService audit;
    private final Json json;

    public SettlementService(JdbcClient jdbc, BusinessIds ids, CommissionService commissions, LedgerService ledger,
                             RequestRepository requests, NotificationService notifications, SettingsService settings,
                             AuditService audit, Json json) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.commissions = commissions;
        this.ledger = ledger;
        this.requests = requests;
        this.notifications = notifications;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
    }

    /** Called inside the completion transaction (request row already locked by caller). */
    public void onCompleted(RequestRepository.Row r, UUID actor) {
        Job j = jdbc.sql("""
                SELECT sr.id AS request_id, sr.customer_user_id, a.partner_id, a.id AS assignment_id,
                       (a.price_snapshot->>'total')::numeric AS total, COALESCE((a.price_snapshot->>'tax')::numeric, 0) AS tax,
                       COALESCE((a.price_snapshot->>'discount')::numeric, 0) AS discount,
                       sr.service_item_id, sr.subcategory_id, sr.category_id, sr.city_id,
                       p.method, p.status AS payment_status
                FROM service_requests sr
                JOIN assignments a ON a.request_id = sr.id AND a.status = 'COMPLETED'
                LEFT JOIN LATERAL (SELECT method, status FROM payments WHERE request_id = sr.id AND status IN ('SUCCEEDED','COLLECT_ON_DELIVERY')
                                   ORDER BY created_at DESC LIMIT 1) p ON TRUE
                WHERE sr.id = :r""").param("r", r.id()).query(Job.class).optional()
                .orElseThrow(() -> BusinessException.invalid("No completed assignment for this request"));

        CommissionService.Resolved rule = commissions.resolve(j.partnerId(), j.serviceItemId(), j.subcategoryId(), j.categoryId(), j.cityId());
        BigDecimal commission = CommissionService.commissionOn(j.total(), rule.ratePercent());
        BigDecimal partnerEarning = j.total().subtract(commission);
        String memo = "Commission " + rule.ratePercent().toPlainString() + "% (" + rule.scopeType() + " rule)";

        jdbc.sql("""
                INSERT INTO invoices(invoice_number, invoice_type, request_id, customer_user_id, line_items, subtotal, tax_amount, discount_amount, total_amount)
                VALUES (:n, 'TAX_INVOICE', :r, :c, CAST(:items AS jsonb), :sub, :tax, :disc, :total)""")
                .param("n", ids.next(BusinessIds.Kind.INVOICE)).param("r", r.id()).param("c", j.customerUserId())
                .param("items", json.write(List.of(Map.of("description", "Service charges", "amount", j.total()))))
                .param("sub", j.total().subtract(j.tax()).add(j.discount())).param("tax", j.tax()).param("disc", j.discount())
                .param("total", j.total()).update();

        boolean cash = "PAY_ON_DELIVERY".equals(j.method());
        if (cash) {
            // Partner collected the full amount in cash; the platform commission becomes a receivable from the partner.
            ledger.post(r.id(), "ASSIGNMENT", j.assignmentId(), List.of(
                    new LedgerService.Entry("CASH_COLLECTED_BY_PARTNER", "DEBIT", j.total(), j.partnerId(), "Cash collected on delivery"),
                    new LedgerService.Entry("PARTNER_EARNING", "CREDIT", partnerEarning, j.partnerId(), "Partner earning"),
                    new LedgerService.Entry("PLATFORM_COMMISSION", "CREDIT", commission, j.partnerId(), memo)));
        } else {
            ledger.post(r.id(), "ASSIGNMENT", j.assignmentId(), List.of(
                    new LedgerService.Entry("PARTNER_EARNING", "CREDIT", partnerEarning, j.partnerId(), "Partner earning"),
                    new LedgerService.Entry("PLATFORM_COMMISSION", "CREDIT", commission, j.partnerId(), memo)));
            int days = settings.getInt("settlement.target_days", 1);
            jdbc.sql("""
                    INSERT INTO partner_payouts(payout_number, partner_id, request_id, amount, status, due_on)
                    VALUES (:n, :p, :r, :a, 'PENDING', :due)""")
                    .param("n", ids.next(BusinessIds.Kind.PAYOUT)).param("p", j.partnerId()).param("r", r.id()).param("a", partnerEarning)
                    .param("due", LocalDate.now().plusDays(days)).update();
        }
        requests.transition(r, RequestStatus.SETTLEMENT_PENDING, actor, "SYSTEM",
                "Commission Rs " + commission.toPlainString() + "; partner earning Rs " + partnerEarning.toPlainString());
        audit.record(actor, "SYSTEM", "SETTLEMENT_CALCULATED", "REQUEST", r.id(), Map.of("commission", commission.toPlainString(),
                "partnerEarning", partnerEarning.toPlainString(), "ruleScope", rule.scopeType(), "cash", cash));
        UUID partnerUser = jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", j.partnerId()).query(UUID.class).single();
        notifications.notifyUser(partnerUser, "Job completed", "Earning Rs " + partnerEarning.toPlainString() + " after commission Rs "
                + commission.toPlainString() + (cash ? " (cash collected)" : " - payout scheduled"), "PARTNER_EARNING", "REQUEST", r.id());
        if (cash) {
            // Nothing to pay out; settled once commission receivable is recorded.
            requests.transition(requests.lock(r.id()), RequestStatus.SETTLED, actor, "SYSTEM", "Cash job settled; commission receivable recorded");
        }
    }

    /** Finance marks a payout as paid (after the bank transfer). */
    @Transactional
    public void markPayoutPaid(UUID payoutId, String bankReference, UUID financeUserId) {
        record Payout(UUID id, UUID partnerId, UUID requestId, BigDecimal amount, String status) {}
        Payout p = jdbc.sql("SELECT id, partner_id, request_id, amount, status FROM partner_payouts WHERE id = :id FOR UPDATE")
                .param("id", payoutId).query(Payout.class).optional().orElseThrow(() -> BusinessException.notFound("Payout"));
        if ("PAID".equals(p.status())) return;
        if (bankReference == null || bankReference.isBlank()) throw BusinessException.invalid("Bank reference is required");
        jdbc.sql("UPDATE partner_payouts SET status = 'PAID', paid_at = now(), reference = :ref WHERE id = :id")
                .param("ref", bankReference).param("id", payoutId).update();
        ledger.post(p.requestId(), "PAYOUT", payoutId, List.of(new LedgerService.Entry("PARTNER_PAYOUT", "DEBIT", p.amount(), p.partnerId(),
                "Payout " + bankReference)));
        audit.record(financeUserId, "FINANCE", "PAYOUT_PAID", "PAYOUT", payoutId, Map.of("amount", p.amount().toPlainString(), "reference", bankReference));
        if (p.requestId() != null) {
            RequestRepository.Row r = requests.lock(p.requestId());
            if (r.isV1() && r.statusEnum() == RequestStatus.SETTLEMENT_PENDING) {
                requests.transition(r, RequestStatus.SETTLED, financeUserId, "FINANCE", "Partner payout completed");
            }
        }
        UUID partnerUser = jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", p.partnerId()).query(UUID.class).single();
        notifications.notifyUser(partnerUser, "Payout sent", "Rs " + p.amount().toPlainString() + " has been paid. Ref: " + bankReference,
                "PARTNER_PAYOUT", "PAYOUT", payoutId);
    }
}
