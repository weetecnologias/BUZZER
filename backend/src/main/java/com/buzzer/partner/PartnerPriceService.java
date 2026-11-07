package com.buzzer.partner;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.ExcelSupport;
import com.buzzer.notification.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Partner price lists. A partner can price any menu item they are approved for. New or changed prices are
 * PENDING until an admin approves them; agents only ever use the last APPROVED price, so a partner editing a
 * price never silently changes quotations or existing bookings (which keep their own price snapshot).
 */
@Service
public class PartnerPriceService {
    public record PriceInput(UUID serviceItemId, BigDecimal price, String notes) {}

    private static final BigDecimal MAX_PRICE = new BigDecimal("10000000");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final NotificationService notifications;

    public PartnerPriceService(JdbcClient jdbc, AuditService audit, NotificationService notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
    }

    private static final String ALLOWED_ITEMS = """
            SELECT DISTINCT i.id FROM partner_capabilities pc JOIN service_items i ON i.subcategory_id = pc.subcategory_id
            WHERE pc.partner_id = :p AND pc.status = 'APPROVED' AND i.active AND NOT i.is_other
              AND (pc.service_item_id IS NULL OR pc.service_item_id = i.id)""";

    /** Every menu item the partner may price, with the catalogue price and the partner's own price/status. */
    public List<Map<String, Object>> priceList(UUID partnerId) {
        return jdbc.sql("""
                SELECT i.id AS service_item_id, i.name, i.menu_group, i.pricing_type, i.base_price AS catalogue_price, i.unit,
                       s.id AS subcategory_id, s.name AS subcategory_name, c.name AS category_name,
                       pp.price, pp.notes, pp.status, pp.approved_price, pp.review_note, pp.updated_at
                FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                LEFT JOIN partner_price_items pp ON pp.service_item_id = i.id AND pp.partner_id = :p AND pp.active
                WHERE i.id IN (""" + ALLOWED_ITEMS + """
                ) ORDER BY c.sort_order, s.sort_order, i.menu_group NULLS LAST, i.sort_order""").param("p", partnerId).query().listOfRows();
    }

    @Transactional
    public Map<String, Object> save(UUID partnerId, UUID partnerUserId, List<PriceInput> prices) {
        if (prices == null || prices.isEmpty()) throw BusinessException.invalid("Enter at least one price");
        if (prices.size() > 500) throw BusinessException.invalid("At most 500 prices at once");
        List<UUID> allowed = jdbc.sql(ALLOWED_ITEMS).param("p", partnerId).query(UUID.class).list();
        int changed = 0;
        for (PriceInput in : prices) {
            if (in.serviceItemId() == null || !allowed.contains(in.serviceItemId())) {
                throw new BusinessException(ErrorCode.PARTNER_NOT_ELIGIBLE, "You can only price services you are approved for", Map.of("serviceItemId", String.valueOf(in.serviceItemId())));
            }
            if (in.price() == null || in.price().signum() < 0 || in.price().compareTo(MAX_PRICE) > 0) throw BusinessException.invalid("Prices must be between 0 and 1,00,00,000");
            if (in.notes() != null && in.notes().length() > 300) throw BusinessException.invalid("Notes must be 300 characters or less");
            changed += jdbc.sql("""
                    INSERT INTO partner_price_items(partner_id, service_item_id, price, notes) VALUES (:p, :i, :pr, :n)
                    ON CONFLICT (partner_id, service_item_id) DO UPDATE SET
                      status = CASE WHEN partner_price_items.price = EXCLUDED.price AND partner_price_items.status = 'APPROVED' THEN 'APPROVED' ELSE 'PENDING' END,
                      price = EXCLUDED.price, notes = EXCLUDED.notes, active = TRUE, updated_at = now()""")
                    .param("p", partnerId).param("i", in.serviceItemId()).param("pr", in.price().setScale(2, java.math.RoundingMode.HALF_UP))
                    .param("n", in.notes()).update();
        }
        audit.record(partnerUserId, "PARTNER", "PRICE_LIST_SUBMITTED", "PARTNER", partnerId, Map.of("items", prices.size()));
        return Map.of("saved", changed, "status", "PENDING_APPROVAL");
    }

    // ----- Excel for partners (works from the web build or a desktop browser) ---------------------

    public byte[] template(UUID partnerId) {
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : priceList(partnerId)) {
            rows.add(new ArrayList<>(java.util.Arrays.asList(r.get("service_item_id").toString(), r.get("category_name"), r.get("subcategory_name"),
                    r.get("menu_group") == null ? "" : r.get("menu_group"), r.get("name"), r.get("catalogue_price") == null ? "" : r.get("catalogue_price"),
                    r.get("unit") == null ? "" : r.get("unit"), r.get("price") == null ? "" : r.get("price"), r.get("notes") == null ? "" : r.get("notes"))));
        }
        return ExcelSupport.write(List.of(new ExcelSupport.SheetData("Prices",
                List.of("service_item_id", "category", "subcategory", "menu_group", "service", "buzzer_listed_price", "unit", "my_price", "notes"), rows)));
    }

    @Transactional
    public Map<String, Object> importExcel(UUID partnerId, UUID partnerUserId, MultipartFile file) {
        List<Map<String, String>> rows = ExcelSupport.read(file, "Prices");
        List<PriceInput> inputs = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<UUID> allowed = jdbc.sql(ALLOWED_ITEMS).param("p", partnerId).query(UUID.class).list();
        for (Map<String, String> r : rows) {
            String priceText = r.getOrDefault("my_price", "").trim();
            if (priceText.isEmpty()) continue;
            String row = r.get("_row");
            UUID id;
            try {
                id = UUID.fromString(r.getOrDefault("service_item_id", "").trim());
            } catch (IllegalArgumentException e) {
                errors.add(Map.of("row", row, "error", "Do not change the service_item_id column"));
                continue;
            }
            BigDecimal price = ExcelSupport.decimal(priceText);
            if (price == null || price.signum() < 0) errors.add(Map.of("row", row, "error", "my_price must be a positive number"));
            else if (!allowed.contains(id)) errors.add(Map.of("row", row, "error", "You are not approved for this service"));
            else inputs.add(new PriceInput(id, price, r.getOrDefault("notes", null)));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Fix the listed rows and upload again - nothing was saved", Map.of("rows", errors));
        }
        if (inputs.isEmpty()) throw BusinessException.invalid("Fill the my_price column for at least one service");
        return save(partnerId, partnerUserId, inputs);
    }

    // ----- Admin review ---------------------------------------------------------------------------

    public List<Map<String, Object>> pending(String status) {
        return jdbc.sql("""
                SELECT pp.id, pp.price, pp.approved_price, pp.notes, pp.status, pp.updated_at, p.id AS partner_id, p.display_name AS partner_name,
                       i.name AS service_name, i.base_price AS catalogue_price, i.unit, i.pricing_type, s.name AS subcategory_name, c.name AS category_name
                FROM partner_price_items pp JOIN partners p ON p.id = pp.partner_id JOIN service_items i ON i.id = pp.service_item_id
                JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                WHERE pp.active AND (CAST(:s AS text) IS NULL OR pp.status = CAST(:s AS text))
                ORDER BY pp.updated_at DESC LIMIT 1000""").param("s", status).query().listOfRows();
    }

    @Transactional
    public int review(List<UUID> ids, boolean approve, String note, UUID adminUserId) {
        if (ids == null || ids.isEmpty()) throw BusinessException.invalid("Select at least one price");
        int n = 0;
        for (UUID id : ids) {
            UUID partner = jdbc.sql(approve ? """
                    UPDATE partner_price_items SET status = 'APPROVED', approved_price = price, review_note = :n, reviewed_by = :a, reviewed_at = now()
                    WHERE id = :id AND status = 'PENDING' RETURNING partner_id""" : """
                    UPDATE partner_price_items SET status = 'REJECTED', review_note = :n, reviewed_by = :a, reviewed_at = now()
                    WHERE id = :id AND status = 'PENDING' RETURNING partner_id""")
                    .param("n", note).param("a", adminUserId).param("id", id).query(UUID.class).optional().orElse(null);
            if (partner != null) n++;
        }
        audit.record(adminUserId, "ADMIN", approve ? "PRICE_LIST_APPROVED" : "PRICE_LIST_REJECTED", "PARTNER_PRICE", null, Map.of("count", n));
        jdbc.sql("""
                SELECT DISTINCT p.user_id FROM partner_price_items pp JOIN partners p ON p.id = pp.partner_id WHERE pp.id IN (:ids)""")
                .param("ids", ids).query(UUID.class).list()
                .forEach(u -> notifications.notifyUser(u, approve ? "Prices approved" : "Some prices were not approved",
                        approve ? "Your updated prices are live for agents." : "Open Services & prices to see the note and resubmit.",
                        "PARTNER_ACCOUNT", "PARTNER", null));
        return n;
    }

    /**
     * Instant quotation from a partner's approved prices for the given request lines.
     * Returns null for the total when any line has no approved price.
     */
    public Map<String, Object> priceFor(UUID partnerId, List<Map<String, Object>> lines) {
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> breakdown = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            UUID item = UUID.fromString(String.valueOf(l.get("service_item_id")));
            int qty = ((Number) l.getOrDefault("quantity", 1)).intValue();
            BigDecimal p = jdbc.sql("""
                    SELECT approved_price FROM partner_price_items WHERE partner_id = :p AND service_item_id = :i AND active AND approved_price IS NOT NULL""")
                    .param("p", partnerId).param("i", item).query(BigDecimal.class).optional().orElse(null);
            if (p == null) {
                missing.add(String.valueOf(l.get("name")));
                continue;
            }
            BigDecimal lineTotal = p.multiply(BigDecimal.valueOf(qty));
            total = total.add(lineTotal);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("name", l.get("name"));
            b.put("quantity", qty);
            b.put("unit_price", p);
            b.put("line_total", lineTotal);
            breakdown.add(b);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("complete", missing.isEmpty());
        out.put("total", missing.isEmpty() ? total : null);
        out.put("breakdown", breakdown);
        out.put("missing", missing);
        return out;
    }
}
