package com.buzzer.finance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

/**
 * Commission resolution with explicit precedence:
 *   PARTNER > SERVICE > SUBCATEGORY > CATEGORY > DEFAULT,
 * and within one scope a city-specific rule beats a global rule, then the most recent effective date wins.
 * Only rules effective on the given date are considered.
 */
@Service
public class CommissionService {
    public record Resolved(UUID ruleId, String scopeType, BigDecimal ratePercent) {}

    private final JdbcClient jdbc;

    public CommissionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Resolved resolve(UUID partnerId, UUID serviceItemId, UUID subcategoryId, UUID categoryId, UUID cityId) {
        return jdbc.sql("""
                SELECT id AS rule_id, scope_type, rate_percent FROM commission_rules
                WHERE active AND effective_from <= CURRENT_DATE AND (effective_to IS NULL OR effective_to >= CURRENT_DATE)
                  AND (city_id IS NULL OR city_id = CAST(:city AS uuid))
                  AND ((scope_type = 'PARTNER' AND scope_id = CAST(:p AS uuid))
                    OR (scope_type = 'SERVICE' AND scope_id = CAST(:svc AS uuid))
                    OR (scope_type = 'SUBCATEGORY' AND scope_id = CAST(:sub AS uuid))
                    OR (scope_type = 'CATEGORY' AND scope_id = CAST(:cat AS uuid))
                    OR scope_type = 'DEFAULT')
                ORDER BY CASE scope_type WHEN 'PARTNER' THEN 1 WHEN 'SERVICE' THEN 2 WHEN 'SUBCATEGORY' THEN 3 WHEN 'CATEGORY' THEN 4 ELSE 5 END,
                         (city_id IS NOT NULL) DESC, effective_from DESC
                LIMIT 1""")
                .param("p", partnerId).param("svc", serviceItemId).param("sub", subcategoryId).param("cat", categoryId).param("city", cityId)
                .query(Resolved.class).optional().orElse(new Resolved(null, "NONE", BigDecimal.ZERO));
    }

    public static BigDecimal commissionOn(BigDecimal amount, BigDecimal ratePercent) {
        return amount.multiply(ratePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public Map<String, Object> preview(UUID partnerId, UUID serviceItemId, UUID subcategoryId, UUID categoryId, UUID cityId, BigDecimal amount) {
        Resolved r = resolve(partnerId, serviceItemId, subcategoryId, categoryId, cityId);
        BigDecimal commission = commissionOn(amount, r.ratePercent());
        return Map.of("rule_id", String.valueOf(r.ruleId()), "scope_type", r.scopeType(), "rate_percent", r.ratePercent(),
                "commission", commission, "partner_earning", amount.subtract(commission));
    }
}
