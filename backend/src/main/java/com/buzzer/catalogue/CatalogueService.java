package com.buzzer.catalogue;

import com.buzzer.common.BusinessException;
import com.buzzer.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the dynamic catalogue. Nothing here knows about specific categories:
 * everything is driven by database configuration.
 */
@Service
public class CatalogueService {
    /** Resolved service context used by the request engine. */
    public record ServiceContext(UUID categoryId, String categoryCode, String workflowTemplate, UUID subcategoryId,
                                 UUID serviceItemId, boolean isOther, boolean payOnDeliveryAllowed, JsonNode formSchema) {}

    private final JdbcClient jdbc;
    private final Json json;

    /** SQL condition (alias c = categories): the category's business vertical is switched on. */
    public static final String VERTICAL_OFF_MESSAGE = "This part of BUZZER is switched off right now. Please try again later.";
    public static final String VERTICAL_ON = "c.vertical IN (SELECT code FROM platform_verticals WHERE enabled)";

    public CatalogueService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Map<String, Object>> categories(UUID cityId) {
        return categories(cityId, null);
    }

    /** Active categories whose vertical is on; optionally only one vertical (MARKETPLACE, SERVICES, TASKS, ASSISTANCE). */
    public List<Map<String, Object>> categories(UUID cityId, String vertical) {
        return jdbc.sql("""
                SELECT c.id, c.code, c.name, c.description, c.icon, c.image_url, c.sort_order, c.workflow_template,
                       c.is_global_special, c.advisory_notice, c.vertical,
                       (SELECT count(*) FROM subcategories s WHERE s.category_id = c.id AND s.active) AS subcategory_count
                FROM categories c
                WHERE c.active AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled) AND (CAST(:v AS text) IS NULL OR c.vertical = CAST(:v AS text))
                  AND (CAST(:city AS uuid) IS NULL OR EXISTS (
                    SELECT 1 FROM category_cities cc WHERE cc.category_id = c.id AND cc.city_id = CAST(:city AS uuid) AND cc.active))
                ORDER BY c.sort_order, c.name""").param("city", cityId).param("v", vertical == null || vertical.isBlank() ? null : vertical.toUpperCase())
                .query().listOfRows();
    }

    public Map<String, Object> category(UUID id) {
        return jdbc.sql("""
                SELECT c.id, c.code, c.name, c.description, c.icon, c.image_url, c.workflow_template, c.is_global_special, c.advisory_notice, c.vertical
                FROM categories c WHERE c.id = :id AND c.active AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled)""").param("id", id).query().listOfRows()
                .stream().findFirst().orElseThrow(() -> BusinessException.notFound("Category"));
    }

    /** Only subcategories that belong to the selected category (contextual navigation). */
    public List<Map<String, Object>> subcategories(UUID categoryId) {
        return jdbc.sql("""
                SELECT s.id, s.code, s.name, s.description, s.icon, s.image_url, s.sort_order,
                       (SELECT count(*) FROM service_items i WHERE i.subcategory_id = s.id AND i.active) AS service_count
                FROM subcategories s WHERE s.category_id = :c AND s.active
                  AND EXISTS (SELECT 1 FROM categories c WHERE c.id = s.category_id AND c.active AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled))
                ORDER BY s.sort_order, s.name""")
                .param("c", categoryId).query().listOfRows();
    }

    /** Only services that belong to the selected subcategory; the "Other ..." item is always last. */
    public List<Map<String, Object>> services(UUID subcategoryId) {
        return jdbc.sql("""
                SELECT id, code, name, description, icon, image_url, menu_group, original_price, deal_label, duration_label, pricing_type, base_price, min_price, max_price, unit, is_other, sort_order
                FROM service_items WHERE subcategory_id = :s AND active
                  AND EXISTS (SELECT 1 FROM subcategories s JOIN categories c ON c.id = s.category_id WHERE s.id = :s AND c.active AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled))
                ORDER BY is_other, menu_group NULLS LAST, sort_order, name""")
                .param("s", subcategoryId).query().listOfRows();
    }

    /** Every active service in a category (the "All" filter), each tagged with its subcategory. */
    public List<Map<String, Object>> servicesInCategory(UUID categoryId) {
        return jdbc.sql("""
                SELECT i.id, i.code, i.name, i.description, i.icon, i.image_url, i.menu_group, i.original_price, i.deal_label, i.duration_label, i.pricing_type, i.base_price, i.min_price, i.max_price,
                       i.unit, i.is_other, s.id AS subcategory_id, s.name AS subcategory_name, s.icon AS subcategory_icon,
                       (SELECT count(DISTINCT pc.partner_id) FROM partner_capabilities pc JOIN partners p ON p.id = pc.partner_id
                         WHERE pc.subcategory_id = s.id AND pc.status = 'APPROVED' AND p.kyc_status = 'VERIFIED'
                           AND p.registration_status = 'APPROVED') AS verified_partners
                FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                WHERE s.category_id = :c AND s.active AND i.active AND c.active AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled)
                ORDER BY s.sort_order, i.is_other, i.menu_group NULLS LAST, i.sort_order, i.name""").param("c", categoryId).query().listOfRows();
    }

    public Map<String, Object> serviceDetail(UUID serviceId) {
        ServiceContext ctx = resolve(null, null, serviceId);
        Map<String, Object> row = jdbc.sql("""
                SELECT i.id, i.code, i.name, i.description, i.image_url, i.original_price, i.deal_label, i.duration_label, i.pricing_type, i.base_price, i.min_price, i.max_price, i.unit,
                       i.is_other, i.pay_on_delivery_allowed, s.id AS subcategory_id, s.name AS subcategory_name,
                       c.id AS category_id, c.name AS category_name, c.workflow_template, c.advisory_notice
                FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                WHERE i.id = :id""").param("id", serviceId).query().listOfRows().getFirst();
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("form_schema", ctx.formSchema());
        out.put("variants", jdbc.sql("SELECT id, name, price_delta FROM service_variants WHERE service_item_id = :id AND active ORDER BY sort_order")
                .param("id", serviceId).query().listOfRows());
        return out;
    }

    public Map<String, Object> categoryForm(UUID categoryId) {
        ServiceContext ctx = resolve(categoryId, null, null);
        return Map.of("category_id", categoryId, "form_schema", ctx.formSchema(), "workflow_template", ctx.workflowTemplate());
    }

    /** Active items with a deal label (configured by admins), newest categories first. */
    public List<Map<String, Object>> deals(int limit) {
        return jdbc.sql("""
                SELECT i.id, i.name, i.description, i.image_url, i.icon, i.pricing_type, i.base_price, i.min_price, i.unit,
                       i.original_price, i.deal_label, s.id AS subcategory_id, s.name AS subcategory_name, s.icon AS subcategory_icon,
                       c.id AS category_id, c.name AS category_name, c.workflow_template
                FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                WHERE i.active AND s.active AND c.active AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled) AND i.deal_label IS NOT NULL
                ORDER BY c.sort_order, s.sort_order, i.sort_order LIMIT :l""").param("l", Math.min(Math.max(limit, 1), 50)).query().listOfRows();
    }

    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of("the", "and", "for", "with", "please", "need", "want", "near",
            "get", "some", "from", "into", "this", "that", "have", "my", "me", "our", "your", "can", "you", "help", "buy", "find", "looking");

    /** Lower-case search words with crude stemming ("leaking" -> "leak", "bags" -> "bag"), at most 6. */
    public static List<String> searchTerms(String q) {
        List<String> out = new java.util.ArrayList<>();
        for (String w : q.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() < 3 || STOP_WORDS.contains(w)) continue;
            String t = w;
            if (t.length() > 5 && t.endsWith("ing")) t = t.substring(0, t.length() - 3);
            else if (t.length() > 4 && t.endsWith("es")) t = t.substring(0, t.length() - 2);
            else if (t.length() > 3 && t.endsWith("s")) t = t.substring(0, t.length() - 1);
            if (!out.contains(t)) out.add(t);
            if (out.size() == 6) break;
        }
        return out;
    }

    /**
     * Item search: the whole phrase, or any of its words, in the item / menu group / subcategory / category name.
     * Ranked by phrase match, then how many words match, then name-starts-with.
     */
    public List<Map<String, Object>> search(String q, int limit) {
        if (q == null || q.trim().length() < 2) return List.of();
        String phrase = q.trim();
        List<String> terms = searchTerms(phrase);
        StringBuilder score = new StringBuilder("0");
        for (int k = 0; k < terms.size(); k++) {
            score.append(" + (CASE WHEN i.name ILIKE :t").append(k).append(" OR i.menu_group ILIKE :t").append(k)
                    .append(" OR s.name ILIKE :t").append(k).append(" OR c.name ILIKE :t").append(k).append(" THEN 1 ELSE 0 END)");
        }
        var stmt = jdbc.sql("""
                SELECT * FROM (
                  SELECT 'SERVICE' AS kind, i.id, i.name, s.name AS subcategory_name, c.name AS category_name, c.vertical,
                         c.id AS category_id, s.id AS subcategory_id, c.workflow_template, i.is_other, i.pricing_type, i.base_price, i.min_price,
                         i.unit, i.image_url, i.icon, s.icon AS subcategory_icon, i.original_price, i.deal_label,
                         (i.name ILIKE :q OR s.name ILIKE :q OR c.name ILIKE :q OR i.menu_group ILIKE :q) AS phrase_hit,
                         (i.name ILIKE :prefix) AS prefix_hit, c.sort_order AS c_ord, s.sort_order AS s_ord, i.sort_order AS i_ord,
                        """ + " " + score + """
                   AS term_hits
                  FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                  WHERE i.active AND s.active AND c.active AND NOT i.is_other AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled)
                ) x WHERE x.phrase_hit OR x.term_hits > 0
                ORDER BY x.phrase_hit DESC, x.term_hits DESC, x.prefix_hit DESC, x.c_ord, x.s_ord, x.i_ord LIMIT :l""")
                .param("q", "%" + phrase + "%").param("prefix", phrase + "%").param("l", Math.min(Math.max(limit, 1), 50));
        for (int k = 0; k < terms.size(); k++) stmt = stmt.param("t" + k, "%" + terms.get(k) + "%");
        return stmt.query().listOfRows();
    }

    /**
     * Resolves and validates the category -> subcategory -> service chain supplied by a client.
     * Rejects mismatched hierarchies (e.g. an electrical service under the plumbing subcategory).
     * Form precedence: service form -> category default form.
     */
    public ServiceContext resolve(UUID categoryId, UUID subcategoryId, UUID serviceItemId) {
        if (serviceItemId != null) {
            var row = jdbc.sql("""
                    SELECT c.id AS category_id, c.code, c.workflow_template, s.id AS subcategory_id, i.id AS service_id,
                           i.is_other, i.pay_on_delivery_allowed, c.vertical IN (SELECT code FROM platform_verticals WHERE enabled) AS vertical_on,
                           COALESCE(i.form_schema, c.default_form_schema)::text AS form
                    FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                    WHERE i.id = :id AND i.active AND s.active AND c.active""")
                    .param("id", serviceItemId).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> BusinessException.notFound("Service"));
            if (!Boolean.TRUE.equals(row.get("vertical_on"))) throw BusinessException.invalid(VERTICAL_OFF_MESSAGE);
            UUID cat = (UUID) row.get("category_id");
            UUID sub = (UUID) row.get("subcategory_id");
            if ((categoryId != null && !categoryId.equals(cat)) || (subcategoryId != null && !subcategoryId.equals(sub))) {
                throw BusinessException.invalid("Selected service does not belong to the selected category/subcategory");
            }
            return new ServiceContext(cat, (String) row.get("code"), (String) row.get("workflow_template"), sub, serviceItemId,
                    (Boolean) row.get("is_other"), (Boolean) row.get("pay_on_delivery_allowed"), json.tree((String) row.get("form")));
        }
        if (categoryId == null) throw BusinessException.invalid("A category or service must be selected");
        var cat = jdbc.sql("""
                SELECT c.id, c.code, c.workflow_template, c.is_global_special, c.default_form_schema::text AS form, c.vertical IN (SELECT code FROM platform_verticals WHERE enabled) AS vertical_on
                FROM categories c WHERE c.id = :id AND c.active""").param("id", categoryId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("Category"));
        if (!Boolean.TRUE.equals(cat.get("vertical_on"))) throw BusinessException.invalid(VERTICAL_OFF_MESSAGE);
        if (subcategoryId != null) {
            Integer ok = jdbc.sql("SELECT count(*) FROM subcategories WHERE id = :s AND category_id = :c AND active")
                    .param("s", subcategoryId).param("c", categoryId).query(Integer.class).single();
            if (ok == 0) throw BusinessException.invalid("Selected subcategory does not belong to the selected category");
        }
        return new ServiceContext(categoryId, (String) cat.get("code"), (String) cat.get("workflow_template"), subcategoryId, null,
                Boolean.TRUE.equals(cat.get("is_global_special")), true, json.tree((String) cat.get("form")));
    }
}
