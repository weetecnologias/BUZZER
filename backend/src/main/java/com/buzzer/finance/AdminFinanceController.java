package com.buzzer.finance;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.identity.CurrentUser;
import com.buzzer.payment.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/finance")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','FINANCE')")
public class AdminFinanceController {
    private static final Set<String> SCOPES = Set.of("PARTNER", "SERVICE", "SUBCATEGORY", "CATEGORY", "DEFAULT");

    public record RuleInput(@NotBlank String scopeType, UUID scopeId, UUID cityId,
                            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal ratePercent, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    public record PayoutInput(@NotBlank String bankReference) {}

    private final JdbcClient jdbc;
    private final SettlementService settlement;
    private final PaymentService payments;
    private final CommissionService commissions;
    private final AuditService audit;

    public AdminFinanceController(JdbcClient jdbc, SettlementService settlement, PaymentService payments, CommissionService commissions,
                                  AuditService audit) {
        this.jdbc = jdbc;
        this.settlement = settlement;
        this.payments = payments;
        this.commissions = commissions;
        this.audit = audit;
    }

    @GetMapping("/commission-rules")
    public ApiResponse<List<Map<String, Object>>> rules() {
        return ApiResponse.of(jdbc.sql("""
                SELECT r.id, r.scope_type, r.scope_id, r.city_id, ci.name AS city_name, r.rate_percent, r.effective_from, r.effective_to, r.active,
                       COALESCE(c.name, s.name, i.name, p.display_name, 'All services') AS scope_name
                FROM commission_rules r LEFT JOIN cities ci ON ci.id = r.city_id
                LEFT JOIN categories c ON r.scope_type = 'CATEGORY' AND c.id = r.scope_id
                LEFT JOIN subcategories s ON r.scope_type = 'SUBCATEGORY' AND s.id = r.scope_id
                LEFT JOIN service_items i ON r.scope_type = 'SERVICE' AND i.id = r.scope_id
                LEFT JOIN partners p ON r.scope_type = 'PARTNER' AND p.id = r.scope_id
                ORDER BY CASE r.scope_type WHEN 'PARTNER' THEN 1 WHEN 'SERVICE' THEN 2 WHEN 'SUBCATEGORY' THEN 3 WHEN 'CATEGORY' THEN 4 ELSE 5 END,
                         r.effective_from DESC""").query().listOfRows());
    }

    @PostMapping("/commission-rules")
    public ApiResponse<Map<String, Object>> createRule(@Valid @RequestBody RuleInput in) {
        if (!SCOPES.contains(in.scopeType())) throw BusinessException.invalid("Unknown scope type");
        if (!"DEFAULT".equals(in.scopeType()) && in.scopeId() == null) throw BusinessException.invalid("Scope is required");
        if (in.effectiveTo() != null && in.effectiveFrom() != null && in.effectiveTo().isBefore(in.effectiveFrom())) {
            throw BusinessException.invalid("Effective-to must be after effective-from");
        }
        UUID id = jdbc.sql("""
                INSERT INTO commission_rules(scope_type, scope_id, city_id, rate_percent, effective_from, effective_to, created_by)
                VALUES (:t, :s, :c, :r, COALESCE(:f, CURRENT_DATE), :to, :by) RETURNING id""")
                .param("t", in.scopeType()).param("s", in.scopeId()).param("c", in.cityId()).param("r", in.ratePercent())
                .param("f", in.effectiveFrom()).param("to", in.effectiveTo()).param("by", CurrentUser.id()).query(UUID.class).single();
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "COMMISSION_RULE_CREATED", "COMMISSION_RULE", id,
                Map.of("scope", in.scopeType(), "rate", in.ratePercent().toPlainString()));
        return ApiResponse.of(Map.of("id", id));
    }

    @PostMapping("/commission-rules/{id}/deactivate")
    public ApiResponse<Map<String, Object>> deactivate(@PathVariable UUID id) {
        jdbc.sql("UPDATE commission_rules SET active = FALSE, effective_to = COALESCE(effective_to, CURRENT_DATE) WHERE id = :id").param("id", id).update();
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "COMMISSION_RULE_DEACTIVATED", "COMMISSION_RULE", id, Map.of());
        return ApiResponse.of(Map.of("active", false));
    }

    @GetMapping("/commission-preview")
    public ApiResponse<Map<String, Object>> preview(@RequestParam(required = false) UUID partnerId, @RequestParam(required = false) UUID serviceItemId,
                                                    @RequestParam(required = false) UUID subcategoryId, @RequestParam(required = false) UUID categoryId,
                                                    @RequestParam(required = false) UUID cityId, @RequestParam BigDecimal amount) {
        return ApiResponse.of(commissions.preview(partnerId, serviceItemId, subcategoryId, categoryId, cityId, amount));
    }

    @GetMapping("/payouts")
    public ApiResponse<List<Map<String, Object>>> payouts(@RequestParam(required = false) String status) {
        return ApiResponse.of(jdbc.sql("""
                SELECT po.id, po.payout_number, po.amount, po.status, po.due_on, po.paid_at, po.reference, p.display_name AS partner_name, r.request_number
                FROM partner_payouts po JOIN partners p ON p.id = po.partner_id LEFT JOIN service_requests r ON r.id = po.request_id
                WHERE CAST(:s AS text) IS NULL OR po.status = CAST(:s AS text) ORDER BY po.due_on NULLS LAST, po.created_at""")
                .param("s", status).query().listOfRows());
    }

    @PostMapping("/payouts/{id}/paid")
    public ApiResponse<Map<String, Object>> markPaid(@PathVariable UUID id, @Valid @RequestBody PayoutInput in) {
        settlement.markPayoutPaid(id, in.bankReference(), CurrentUser.id());
        return ApiResponse.of(Map.of("status", "PAID"));
    }

    @GetMapping("/refunds")
    public ApiResponse<List<Map<String, Object>>> refunds(@RequestParam(required = false) String status) {
        return ApiResponse.of(jdbc.sql("""
                SELECT rf.id, rf.refund_number, rf.amount, rf.reason, rf.status, rf.created_at, p.payment_number, p.test_mode, r.request_number
                FROM refunds rf JOIN payments p ON p.id = rf.payment_id LEFT JOIN service_requests r ON r.id = p.request_id
                WHERE CAST(:s AS text) IS NULL OR rf.status = CAST(:s AS text) ORDER BY rf.created_at DESC""").param("s", status).query().listOfRows());
    }

    @PostMapping("/refunds/{id}/approve")
    public ApiResponse<Map<String, Object>> approveRefund(@PathVariable UUID id) {
        payments.approveRefund(id, CurrentUser.id());
        return ApiResponse.of(Map.of("status", "COMPLETED"));
    }

    @GetMapping("/payments")
    public ApiResponse<List<Map<String, Object>>> paymentsList(@RequestParam(required = false) String status) {
        return ApiResponse.of(jdbc.sql("""
                SELECT p.id, p.payment_number, p.purpose, p.method, p.provider, p.amount, p.status, p.failure_reason, p.test_mode, p.created_at,
                       r.request_number, u.full_name AS payer_name
                FROM payments p LEFT JOIN service_requests r ON r.id = p.request_id JOIN users u ON u.id = p.payer_user_id
                WHERE CAST(:s AS text) IS NULL OR p.status = CAST(:s AS text) ORDER BY p.created_at DESC LIMIT 500""").param("s", status).query().listOfRows());
    }

    /** Business report: revenue/commission by category; downloadable as CSV. */
    @GetMapping("/reports/category-performance")
    public ApiResponse<List<Map<String, Object>>> categoryReport() {
        return ApiResponse.of(categoryRows());
    }

    @GetMapping(value = "/reports/category-performance.csv")
    public ResponseEntity<byte[]> categoryReportCsv() {
        StringBuilder sb = new StringBuilder("category,requests,completed,gross_value,commission\n");
        for (Map<String, Object> r : categoryRows()) {
            sb.append(csv(r.get("category_name"))).append(',').append(r.get("requests")).append(',').append(r.get("completed")).append(',')
                    .append(r.get("gross_value")).append(',').append(r.get("commission")).append('\n');
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=category-performance.csv")
                .contentType(MediaType.parseMediaType("text/csv")).body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> categoryRows() {
        return jdbc.sql("""
                SELECT c.name AS category_name, count(r.id) AS requests,
                       count(r.id) FILTER (WHERE r.status IN ('COMPLETED','SETTLEMENT_PENDING','SETTLED')) AS completed,
                       COALESCE(sum((a.price_snapshot->>'total')::numeric) FILTER (WHERE a.status = 'COMPLETED'), 0) AS gross_value,
                       COALESCE((SELECT sum(le.amount) FROM ledger_entries le JOIN service_requests r2 ON r2.id = le.request_id
                                 WHERE r2.category_id = c.id AND le.account = 'PLATFORM_COMMISSION'), 0) AS commission
                FROM categories c LEFT JOIN service_requests r ON r.category_id = c.id
                LEFT JOIN assignments a ON a.request_id = r.id AND a.status = 'COMPLETED'
                GROUP BY c.id, c.name, c.sort_order ORDER BY c.sort_order""").query().listOfRows();
    }

    private static String csv(Object v) {
        String s = v == null ? "" : v.toString();
        // Prevent CSV formula injection in spreadsheet tools.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
