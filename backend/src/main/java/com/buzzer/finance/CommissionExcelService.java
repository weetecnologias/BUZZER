package com.buzzer.finance;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.ExcelSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Commission rates in bulk via Excel: one row per category / subcategory (and optionally per menu item).
 * Fill new_rate for the rows to change. Each change creates a new dated rule and ends the previous rule for the
 * same scope the day before - history is kept, nothing is overwritten. All-or-nothing with row-level errors.
 */
@Service
@RestController
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','FINANCE')")
public class CommissionExcelService {
    private static final List<String> COLUMNS = List.of("scope_type", "category_code", "subcategory_code", "item_code", "name",
            "current_rate_percent", "new_rate_percent", "effective_from");

    private record Change(int row, String scopeType, UUID scopeId, String name, BigDecimal rate, LocalDate from, List<String> errors) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public CommissionExcelService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/api/v1/admin/finance/commission/excel")
    public ResponseEntity<byte[]> template() {
        List<List<Object>> rows = new ArrayList<>();
        jdbc.sql("""
                SELECT 'CATEGORY' AS scope, c.code AS cc, '' AS sc, c.name, c.sort_order AS o1, 0 AS o2,
                       (SELECT r.rate_percent FROM commission_rules r WHERE r.scope_type = 'CATEGORY' AND r.scope_id = c.id AND r.active
                          AND r.city_id IS NULL AND r.effective_from <= CURRENT_DATE AND (r.effective_to IS NULL OR r.effective_to >= CURRENT_DATE)
                          ORDER BY r.effective_from DESC LIMIT 1) AS rate
                FROM categories c
                UNION ALL
                SELECT 'SUBCATEGORY', c.code, s.code, c.name || ' > ' || s.name, c.sort_order, s.sort_order,
                       (SELECT r.rate_percent FROM commission_rules r WHERE r.scope_type = 'SUBCATEGORY' AND r.scope_id = s.id AND r.active
                          AND r.city_id IS NULL AND r.effective_from <= CURRENT_DATE AND (r.effective_to IS NULL OR r.effective_to >= CURRENT_DATE)
                          ORDER BY r.effective_from DESC LIMIT 1)
                FROM subcategories s JOIN categories c ON c.id = s.category_id
                ORDER BY 5, 6""").query().listOfRows().forEach(r -> rows.add(new ArrayList<>(List.of(
                r.get("scope"), r.get("cc"), r.get("sc"), "", r.get("name"), r.get("rate") == null ? "" : r.get("rate"), "", ""))));
        BigDecimal def = jdbc.sql("""
                SELECT rate_percent FROM commission_rules WHERE scope_type = 'DEFAULT' AND active AND city_id IS NULL
                  AND effective_from <= CURRENT_DATE AND (effective_to IS NULL OR effective_to >= CURRENT_DATE) ORDER BY effective_from DESC LIMIT 1""")
                .query(BigDecimal.class).optional().orElse(null);
        rows.addFirst(new ArrayList<>(List.of("DEFAULT", "", "", "", "All services (fallback)", def == null ? "" : def, "", "")));
        List<List<Object>> help = List.of(
                List.of("Fill new_rate_percent only on rows you want to change (0-100). Leave others blank."),
                List.of("Precedence: partner > menu item > subcategory > category > default. Blank current rate = inherited from the level above."),
                List.of("Add a row with scope_type ITEM plus category_code, subcategory_code and item_code to set a rate for one menu item."),
                List.of("effective_from (YYYY-MM-DD) is optional - blank means today. The previous rule ends the day before."),
                List.of("Nothing is applied if any row has an error."));
        return ExcelSupport.download("buzzer-commission-rates.xlsx", ExcelSupport.write(List.of(
                new ExcelSupport.SheetData("Commission", COLUMNS, rows),
                new ExcelSupport.SheetData("Instructions", List.of("Notes"), help.stream().map(l -> (List<Object>) new ArrayList<Object>(l)).toList()))));
    }

    @PostMapping("/api/v1/admin/finance/commission/excel/preview")
    public com.buzzer.common.ApiResponse<Map<String, Object>> preview(@RequestParam("file") MultipartFile file) {
        return com.buzzer.common.ApiResponse.of(summary(parse(file)));
    }

    @PostMapping("/api/v1/admin/finance/commission/excel/import")
    @Transactional
    public com.buzzer.common.ApiResponse<Map<String, Object>> importFile(@RequestParam("file") MultipartFile file) {
        List<Change> changes = parse(file);
        Map<String, Object> s = summary(changes);
        if ((Integer) s.get("error_rows") > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Fix the highlighted rows and upload again - nothing was changed", Map.of("preview", s));
        }
        UUID by = com.buzzer.identity.CurrentUser.id();
        for (Change c : changes) {
            jdbc.sql("""
                    UPDATE commission_rules SET effective_to = CAST(:from AS date) - 1
                    WHERE scope_type = :t AND scope_id IS NOT DISTINCT FROM CAST(:s AS uuid) AND city_id IS NULL AND active
                      AND (effective_to IS NULL OR effective_to >= CAST(:from AS date))""")
                    .param("t", c.scopeType()).param("s", c.scopeId()).param("from", c.from()).update();
            jdbc.sql("""
                    INSERT INTO commission_rules(scope_type, scope_id, rate_percent, effective_from, created_by)
                    VALUES (:t, CAST(:s AS uuid), :r, :from, :by)""")
                    .param("t", c.scopeType()).param("s", c.scopeId()).param("r", c.rate()).param("from", c.from()).param("by", by).update();
        }
        audit.record(by, "FINANCE", "COMMISSION_EXCEL_IMPORTED", "COMMISSION_RULE", null, Map.of("changes", changes.size()));
        Map<String, Object> out = new LinkedHashMap<>(s);
        out.put("imported", true);
        return com.buzzer.common.ApiResponse.of(out);
    }

    private Map<String, Object> summary(List<Change> changes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int errors = 0;
        for (Change c : changes) {
            if (!c.errors().isEmpty()) errors++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", c.row());
            m.put("scope_type", c.scopeType());
            m.put("name", c.name());
            m.put("new_rate_percent", c.rate());
            m.put("effective_from", c.from() == null ? null : c.from().toString());
            m.put("errors", c.errors());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changes", changes.size() - errors);
        out.put("error_rows", errors);
        out.put("rows", rows);
        return out;
    }

    private List<Change> parse(MultipartFile file) {
        List<Map<String, String>> rows = ExcelSupport.read(file, "Commission");
        if (!rows.isEmpty() && !rows.getFirst().containsKey("new_rate_percent")) throw BusinessException.invalid("Missing column 'new_rate_percent' - use the BUZZER template");
        List<Change> out = new ArrayList<>();
        for (Map<String, String> r : rows) {
            String rateText = r.getOrDefault("new_rate_percent", "").trim();
            if (rateText.isEmpty()) continue; // unchanged row
            List<String> e = new ArrayList<>();
            int row = Integer.parseInt(r.get("_row"));
            BigDecimal rate = ExcelSupport.decimal(rateText.replace("%", ""));
            if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) e.add("new_rate_percent must be between 0 and 100");
            LocalDate from = LocalDate.now();
            String f = r.getOrDefault("effective_from", "").trim();
            if (!f.isEmpty()) {
                try {
                    from = LocalDate.parse(f);
                } catch (DateTimeParseException ex) {
                    e.add("effective_from must be YYYY-MM-DD");
                }
            }
            String type = r.getOrDefault("scope_type", "").trim().toUpperCase();
            String cat = r.getOrDefault("category_code", "").trim().toUpperCase();
            String sub = r.getOrDefault("subcategory_code", "").trim().toUpperCase();
            String item = r.getOrDefault("item_code", "").trim().toUpperCase();
            UUID scope = null;
            String scopeType = type;
            switch (type) {
                case "DEFAULT" -> { }
                case "CATEGORY" -> scope = jdbc.sql("SELECT id FROM categories WHERE code = :c").param("c", cat).query(UUID.class).optional().orElse(null);
                case "SUBCATEGORY" -> scope = jdbc.sql("SELECT s.id FROM subcategories s JOIN categories c ON c.id = s.category_id WHERE c.code = :c AND s.code = :s")
                        .param("c", cat).param("s", sub).query(UUID.class).optional().orElse(null);
                case "ITEM", "SERVICE" -> {
                    scopeType = "SERVICE";
                    scope = jdbc.sql("""
                            SELECT i.id FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                            WHERE c.code = :c AND s.code = :s AND i.code = :i""").param("c", cat).param("s", sub).param("i", item)
                            .query(UUID.class).optional().orElse(null);
                }
                default -> e.add("scope_type must be DEFAULT, CATEGORY, SUBCATEGORY or ITEM");
            }
            if (!"DEFAULT".equals(type) && scope == null && e.isEmpty()) e.add("No such " + type.toLowerCase() + " for the given codes");
            out.add(new Change(row, scopeType, scope, r.getOrDefault("name", ""), rate, from, e));
        }
        if (out.isEmpty()) throw BusinessException.invalid("No rows have a new_rate_percent - nothing to change");
        return out;
    }
}
