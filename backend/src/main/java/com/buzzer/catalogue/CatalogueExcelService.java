package com.buzzer.catalogue;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.ExcelSupport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk catalogue management through Excel.
 *
 * One row describes one menu item together with its category, subcategory and (optional) menu group, so the
 * whole hierarchy Category > Subcategory > Menu group > Item is defined in a single flat sheet. Rows may also
 * define only a category, or a category + subcategory without items. Existing records are matched by code and
 * updated; new codes are created. Every row is validated and the import is all-or-nothing: if any row has an
 * error nothing is written, and each error is reported with its row number.
 */
@Service
public class CatalogueExcelService {
    public static final List<String> COLUMNS = List.of(
            "category_code", "category_name", "category_workflow", "category_icon", "category_image_url",
            "subcategory_code", "subcategory_name", "subcategory_icon", "menu_group",
            "item_code", "item_name", "description", "pricing_type", "base_price", "min_price", "max_price", "unit",
            "duration", "image_url", "pay_on_delivery", "active", "sort_order");

    private static final Set<String> WORKFLOWS = Set.of("MENU_DRIVEN", "PROPERTY_REQUIREMENT", "CATEGORY_SPECIAL_REQUEST",
            "GLOBAL_SPECIAL_REQUEST", "JOB_RECRUITMENT", "EVENT_BRIEF", "API_PRICED");
    private static final Set<String> PRICING = Set.of("FIXED", "STARTING_FROM", "RANGE", "PER_UNIT", "HOURLY", "VISIT_PLUS_LABOUR",
            "PACKAGE", "QUOTE_REQUIRED", "API_PRICED");

    public record RowResult(int row, String action, String category, String subcategory, String item, List<String> errors) {}

    private record Parsed(int row, String catCode, String catName, String workflow, String catIcon, String catImage, String subCode,
                          String subName, String subIcon, String group, String itemCode, String itemName, String description,
                          String pricingType, BigDecimal base, BigDecimal min, BigDecimal max, String unit, String duration, String image,
                          Boolean pod, Boolean active, Integer sort, List<String> errors) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public CatalogueExcelService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Template prefilled with the live catalogue (so editing = download, change, upload). */
    public byte[] template(boolean prefill) {
        List<List<Object>> rows = new ArrayList<>();
        if (prefill) {
            jdbc.sql("""
                    SELECT c.code AS cc, c.name AS cn, c.workflow_template AS cw, c.icon AS ci, c.image_url AS cimg,
                           s.code AS sc, s.name AS sn, s.icon AS si, i.menu_group, i.code AS ic, i.name AS iname, i.description,
                           i.pricing_type, i.base_price, i.min_price, i.max_price, i.unit, i.duration_label, i.image_url,
                           i.pay_on_delivery_allowed, i.active, i.sort_order
                    FROM categories c LEFT JOIN subcategories s ON s.category_id = c.id
                    LEFT JOIN service_items i ON i.subcategory_id = s.id AND NOT i.is_other
                    ORDER BY c.sort_order, s.sort_order, i.menu_group NULLS LAST, i.sort_order""").query().listOfRows().forEach(r -> rows.add(new ArrayList<>(List.of(
                    nz(r.get("cc")), nz(r.get("cn")), nz(r.get("cw")), nz(r.get("ci")), nz(r.get("cimg")), nz(r.get("sc")), nz(r.get("sn")),
                    nz(r.get("si")), nz(r.get("menu_group")), nz(r.get("ic")), nz(r.get("iname")), nz(r.get("description")),
                    nz(r.get("pricing_type")), num(r.get("base_price")), num(r.get("min_price")), num(r.get("max_price")), nz(r.get("unit")),
                    nz(r.get("duration_label")), nz(r.get("image_url")), r.get("ic") == null ? "" : (Boolean.TRUE.equals(r.get("pay_on_delivery_allowed")) ? "Y" : "N"),
                    r.get("ic") == null ? "" : (Boolean.TRUE.equals(r.get("active")) ? "Y" : "N"), num(r.get("sort_order"))))));
        } else {
            rows.add(new ArrayList<>(List.of("HOME_REPAIRS", "Home Repairs & Maintenance", "MENU_DRIVEN", "tools", "", "ELECTRICAL", "Electrician", "bolt",
                    "Fans", "FAN_REPAIR", "Fan Repair", "Regulator, capacitor and noise issues", "STARTING_FROM", 149, "", "", "per fan", "30 min",
                    "/api/v1/media/<uploaded-image-id> or https://...", "Y", "Y", 1)));
        }
        List<List<Object>> help = List.of(
                List.of("How it works", "One row = one menu item, with its category, subcategory and optional menu group (sub-subcategory)."),
                List.of("Matching", "Rows are matched by code. Existing codes are updated, new codes are created. Codes: letters, digits, underscore."),
                List.of("Only a category", "Fill only category_code + category_name to create/update a category."),
                List.of("Category + subcategory", "Fill category and subcategory columns, leave item columns empty."),
                List.of("menu_group", "Optional heading inside a subcategory, e.g. Electrician > Fans > Fan Repair."),
                List.of("pricing_type", String.join(", ", PRICING)),
                List.of("category_workflow", String.join(", ", WORKFLOWS) + " (MENU_DRIVEN = add to cart)"),
                List.of("Images", "Upload images in Admin > Catalogue > Image library, then paste the /api/v1/media/... URL; or use any https:// image URL."),
                List.of("Yes/No columns", "pay_on_delivery and active: Y or N (blank = Y)."),
                List.of("Safety", "Nothing is imported if any row has an error. Upload shows a preview first."),
                List.of("Deleting", "Items are never deleted by import - set active = N to hide them."));
        return ExcelSupport.write(List.of(
                new ExcelSupport.SheetData("Catalogue", COLUMNS, rows),
                new ExcelSupport.SheetData("Instructions", List.of("Topic", "Details"), help.stream().map(l -> (List<Object>) new ArrayList<Object>(l)).toList())));
    }

    public Map<String, Object> preview(MultipartFile file) {
        List<Parsed> parsed = parse(file);
        return summary(parsed, false);
    }

    @Transactional
    public Map<String, Object> apply(MultipartFile file, UUID adminUserId) {
        List<Parsed> parsed = parse(file);
        Map<String, Object> s = summary(parsed, true);
        if ((Integer) s.get("error_rows") > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Fix the highlighted rows and upload again - nothing was imported", Map.of("preview", s));
        }
        Map<String, UUID> cats = new HashMap<>();
        Map<String, UUID> subs = new HashMap<>();
        int items = 0;
        for (Parsed p : parsed) {
            UUID cat = cats.computeIfAbsent(p.catCode(), code -> jdbc.sql("""
                    INSERT INTO categories(code, name, workflow_template, icon, image_url, sort_order)
                    VALUES (:c, :n, COALESCE(:w, 'MENU_DRIVEN'), :i, :img, (SELECT COALESCE(max(sort_order), 0) + 1 FROM categories))
                    ON CONFLICT (code) DO UPDATE SET name = COALESCE(EXCLUDED.name, categories.name),
                      workflow_template = COALESCE(:w, categories.workflow_template), icon = COALESCE(:i, categories.icon),
                      image_url = COALESCE(:img, categories.image_url), updated_at = now()
                    RETURNING id""").param("c", code).param("n", p.catName()).param("w", p.workflow()).param("i", p.catIcon())
                    .param("img", p.catImage()).query(UUID.class).single());
            if (p.subCode() == null) continue;
            UUID sub = subs.computeIfAbsent(p.catCode() + "/" + p.subCode(), k -> jdbc.sql("""
                    INSERT INTO subcategories(category_id, code, name, icon, sort_order)
                    VALUES (:cat, :c, :n, :i, (SELECT COALESCE(max(sort_order), 0) + 1 FROM subcategories WHERE category_id = :cat))
                    ON CONFLICT (category_id, code) DO UPDATE SET name = COALESCE(EXCLUDED.name, subcategories.name),
                      icon = COALESCE(:i, subcategories.icon), active = TRUE, updated_at = now()
                    RETURNING id""").param("cat", cat).param("c", p.subCode()).param("n", p.subName()).param("i", p.subIcon()).query(UUID.class).single());
            jdbc.sql("""
                    INSERT INTO service_items(subcategory_id, code, name, pricing_type, is_other, sort_order)
                    SELECT :s, 'OTHER_' || code, 'Other ' || name || ' Requirement', 'QUOTE_REQUIRED', TRUE, 999 FROM subcategories WHERE id = :s
                    ON CONFLICT (subcategory_id, code) DO NOTHING""").param("s", sub).update();
            if (p.itemCode() == null) continue;
            jdbc.sql("""
                    INSERT INTO service_items(subcategory_id, code, name, description, pricing_type, base_price, min_price, max_price, unit,
                      duration_label, image_url, menu_group, pay_on_delivery_allowed, active, sort_order)
                    VALUES (:s, :c, :n, :d, :pt, :b, :min, :max, :u, :dur, :img, :g, :pod, :a,
                      COALESCE(:so, (SELECT COALESCE(max(sort_order), 0) + 1 FROM service_items WHERE subcategory_id = :s AND NOT is_other)))
                    ON CONFLICT (subcategory_id, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                      pricing_type = EXCLUDED.pricing_type, base_price = EXCLUDED.base_price, min_price = EXCLUDED.min_price,
                      max_price = EXCLUDED.max_price, unit = EXCLUDED.unit, duration_label = EXCLUDED.duration_label,
                      image_url = EXCLUDED.image_url, menu_group = EXCLUDED.menu_group, pay_on_delivery_allowed = EXCLUDED.pay_on_delivery_allowed,
                      active = EXCLUDED.active, sort_order = COALESCE(:so, service_items.sort_order), updated_at = now()""")
                    .param("s", sub).param("c", p.itemCode()).param("n", p.itemName()).param("d", p.description()).param("pt", p.pricingType())
                    .param("b", p.base()).param("min", p.min()).param("max", p.max()).param("u", p.unit()).param("dur", p.duration())
                    .param("img", p.image()).param("g", p.group()).param("pod", p.pod() == null || p.pod()).param("a", p.active() == null || p.active())
                    .param("so", p.sort()).update();
            items++;
        }
        jdbc.sql("INSERT INTO category_cities(category_id, city_id) SELECT c.id, ci.id FROM categories c CROSS JOIN cities ci ON CONFLICT DO NOTHING").update();
        audit.record(adminUserId, "ADMIN", "CATALOGUE_EXCEL_IMPORTED", "CATALOGUE", null, Map.of("rows", parsed.size(), "items", items));
        Map<String, Object> out = new LinkedHashMap<>(s);
        out.put("imported", true);
        return out;
    }

    private Map<String, Object> summary(List<Parsed> parsed, boolean forApply) {
        List<RowResult> results = new ArrayList<>();
        int errors = 0, newRows = 0, updates = 0;
        Map<String, Boolean> seenItem = new HashMap<>();
        for (Parsed p : parsed) {
            List<String> errs = new ArrayList<>(p.errors());
            String key = p.catCode() + "/" + p.subCode() + "/" + p.itemCode();
            if (p.itemCode() != null && seenItem.put(key, true) != null) errs.add("Duplicate item code in this file");
            String action;
            if (!errs.isEmpty()) {
                action = "ERROR";
                errors++;
            } else if (exists(p)) {
                action = "UPDATE";
                updates++;
            } else {
                action = "NEW";
                newRows++;
            }
            results.add(new RowResult(p.row(), action, p.catCode(), p.subCode(), p.itemCode() == null ? null : p.itemName(), errs));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_rows", parsed.size());
        out.put("new_rows", newRows);
        out.put("update_rows", updates);
        out.put("error_rows", errors);
        out.put("rows", results);
        return out;
    }

    private boolean exists(Parsed p) {
        if (p.itemCode() != null) {
            return jdbc.sql("""
                    SELECT count(*) FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                    WHERE c.code = :c AND s.code = :s AND i.code = :i""").param("c", p.catCode()).param("s", p.subCode()).param("i", p.itemCode())
                    .query(Integer.class).single() > 0;
        }
        if (p.subCode() != null) {
            return jdbc.sql("SELECT count(*) FROM subcategories s JOIN categories c ON c.id = s.category_id WHERE c.code = :c AND s.code = :s")
                    .param("c", p.catCode()).param("s", p.subCode()).query(Integer.class).single() > 0;
        }
        return jdbc.sql("SELECT count(*) FROM categories WHERE code = :c").param("c", p.catCode()).query(Integer.class).single() > 0;
    }

    private List<Parsed> parse(MultipartFile file) {
        List<Map<String, String>> rows = ExcelSupport.read(file, "Catalogue");
        if (rows.isEmpty()) throw BusinessException.invalid("The sheet has no data rows");
        if (!rows.getFirst().containsKey("category_code")) throw BusinessException.invalid("Missing column 'category_code' - please use the BUZZER template");
        List<Parsed> out = new ArrayList<>();
        for (Map<String, String> r : rows) {
            List<String> e = new ArrayList<>();
            int row = Integer.parseInt(r.get("_row"));
            String catName = blank(r.get("category_name"));
            String catCode = code(r.get("category_code"), catName, "category_code", e);
            if (catCode == null) e.add("category_code (or category_name) is required");
            String workflow = upper(r.get("category_workflow"));
            if (workflow != null && !WORKFLOWS.contains(workflow)) e.add("Unknown category_workflow " + workflow);
            String subName = blank(r.get("subcategory_name"));
            String subCode = code(r.get("subcategory_code"), subName, "subcategory_code", e);
            String itemName = blank(r.get("item_name"));
            String itemCode = code(r.get("item_code"), itemName, "item_code", e);
            if (itemCode != null && subCode == null) e.add("An item needs a subcategory");
            if (itemCode != null && itemName == null) e.add("item_name is required");
            if (catCode != null && catName == null && !categoryExists(catCode)) e.add("category_name is required for a new category");
            String pricing = upper(r.get("pricing_type"));
            if (itemCode != null) {
                if (pricing == null) pricing = "QUOTE_REQUIRED";
                if (!PRICING.contains(pricing)) e.add("Unknown pricing_type " + pricing);
            }
            BigDecimal base = money(r.get("base_price"), "base_price", e);
            BigDecimal min = money(r.get("min_price"), "min_price", e);
            BigDecimal max = money(r.get("max_price"), "max_price", e);
            if (itemCode != null && pricing != null && !Set.of("QUOTE_REQUIRED", "API_PRICED", "RANGE").contains(pricing) && base == null) {
                e.add("base_price is required for pricing_type " + pricing);
            }
            if ("RANGE".equals(pricing) && (min == null || max == null)) e.add("RANGE needs min_price and max_price");
            if (min != null && max != null && min.compareTo(max) > 0) e.add("min_price cannot exceed max_price");
            String image = blank(r.get("image_url"));
            String catImage = blank(r.get("category_image_url"));
            for (String img : new String[]{image, catImage}) {
                if (img != null && !(img.startsWith("https://") || img.matches("^/api/v1/media/[0-9a-fA-F-]{36}$"))) {
                    e.add("Image URL must start with https:// or be an uploaded /api/v1/media/... link");
                }
            }
            Integer sort = null;
            String so = blank(r.get("sort_order"));
            if (so != null) {
                try {
                    sort = (int) Double.parseDouble(so);
                } catch (NumberFormatException ex) {
                    e.add("sort_order must be a number");
                }
            }
            out.add(new Parsed(row, catCode, catName, workflow, blank(r.get("category_icon")), catImage, subCode, subName,
                    blank(r.get("subcategory_icon")), limit(blank(r.get("menu_group")), 80), itemCode, limit(itemName, 160),
                    limit(blank(r.get("description")), 2000), pricing, base, min, max, limit(blank(r.get("unit")), 40),
                    limit(blank(r.get("duration")), 40), image, yesNo(r.get("pay_on_delivery"), "pay_on_delivery", e),
                    yesNo(r.get("active"), "active", e), sort, e));
        }
        return out;
    }

    private boolean categoryExists(String code) {
        return jdbc.sql("SELECT count(*) FROM categories WHERE code = :c").param("c", code).query(Integer.class).single() > 0;
    }

    private static String code(String raw, String name, String col, List<String> e) {
        String v = blank(raw);
        if (v == null && name != null) v = name;
        if (v == null) return null;
        String c = v.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (c.length() < 2 || c.length() > 80) {
            e.add(col + " must be 2-80 letters/digits");
            return null;
        }
        return c;
    }

    private static BigDecimal money(String v, String col, List<String> e) {
        if (blank(v) == null) return null;
        BigDecimal d = ExcelSupport.decimal(v);
        if (d == null || d.signum() < 0) {
            e.add(col + " must be a positive number");
            return null;
        }
        return d;
    }

    private static Boolean yesNo(String v, String col, List<String> e) {
        String b = blank(v);
        if (b == null) return null;
        String u = b.toUpperCase();
        if (u.equals("Y") || u.equals("YES") || u.equals("TRUE") || u.equals("1")) return true;
        if (u.equals("N") || u.equals("NO") || u.equals("FALSE") || u.equals("0")) return false;
        e.add(col + " must be Y or N");
        return null;
    }

    private static String upper(String v) {
        String b = blank(v);
        return b == null ? null : b.toUpperCase().replace(' ', '_');
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String limit(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    private static Object nz(Object v) {
        return v == null ? "" : v;
    }

    private static Object num(Object v) {
        return v == null ? "" : v;
    }
}
