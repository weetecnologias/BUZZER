package com.buzzer.platform;

import com.buzzer.catalogue.CatalogueService;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * BUZZER's business verticals (Marketplace, Services, Tasks, Assistance), their on/off switches, featured shops and the
 * universal search that suggests the right workflow. Suggestions are links only - nothing is created until the
 * customer confirms in the matching flow.
 */
@Service
public class PlatformService {
    public static final Set<String> VERTICALS = Set.of("MARKETPLACE", "SERVICES", "TASKS", "ASSISTANCE");

    private static final List<String> TASK_WORDS = List.of("pickup", "pick up", "pick-up", "drop", "deliver", "courier", "parcel", "document",
            "collect", "errand", "send to", "bring");
    private static final List<String> SERVICE_WORDS = List.of("repair", "fix", "install", "service", "servicing", "leak", "broken", "not working",
            "clean", "paint", "plumber", "electrician", "carpenter", "technician", "mechanic", "pest", "maid", "driver", "helper");
    private static final List<String> RFQ_WORDS = List.of("bulk", "wholesale", "quotation", "quote", "quotes", "bags", "tonne", "ton ", "tons", "kg",
            "litre", "liter", "pcs", "pieces", "boxes", "carton", "dozen", "sq ft", "sqft");
    private static final List<String> BUY_WORDS = List.of("buy", "price", "cost", "purchase", "shop", "store", "near me", "order");
    private static final List<String> ASSIST_WORDS = List.of("help", "plan", "arrange", "organise", "organize", "ticket", "travel", "trip", "wedding",
            "party", "event", "book a", "find me", "someone to");
    private static final Pattern BULK_QTY = Pattern.compile("\\b([1-9]\\d{1,5})\\s*(x|nos|units|bags|kg|pcs|pieces|boxes|litres|liters|meters|metres|ft)?\\b");

    private final JdbcClient jdbc;
    private final CatalogueService catalogue;
    private final AuditService audit;

    public PlatformService(JdbcClient jdbc, CatalogueService catalogue, AuditService audit) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.audit = audit;
    }

    public List<Map<String, Object>> verticals(boolean includeDisabled) {
        return jdbc.sql("""
                SELECT v.code, v.name, v.tagline, v.icon, v.enabled, v.sort_order,
                       (SELECT count(*) FROM categories c WHERE c.vertical = v.code AND c.active) AS category_count
                FROM platform_verticals v WHERE :all OR v.enabled ORDER BY v.sort_order""").param("all", includeDisabled).query().listOfRows();
    }

    public boolean enabled(String code) {
        return jdbc.sql("SELECT enabled FROM platform_verticals WHERE code = :c").param("c", code).query(Boolean.class).optional().orElse(false);
    }

    @Transactional
    public Map<String, Object> setEnabled(String code, boolean enabled, UUID adminUserId) {
        if (!VERTICALS.contains(code)) throw BusinessException.notFound("Vertical");
        jdbc.sql("UPDATE platform_verticals SET enabled = :e, updated_by = :u, updated_at = now() WHERE code = :c")
                .param("e", enabled).param("u", adminUserId).param("c", code).update();
        audit.record(adminUserId, "ADMIN", enabled ? "VERTICAL_ENABLED" : "VERTICAL_DISABLED", "PLATFORM_VERTICAL", null, Map.of("vertical", code));
        return verticals(true).stream().filter(v -> code.equals(v.get("code"))).findFirst().orElseThrow();
    }

    /** Approved, verified partners with an approved marketplace capability ("Shops on BUZZER"). Location-based discovery comes with Stage 5. */
    public List<Map<String, Object>> featuredShops(int limit) {
        if (!enabled("MARKETPLACE")) return List.of();
        return jdbc.sql("""
                SELECT p.id, p.display_name, p.business_name, p.partner_type, p.rating_avg, p.rating_count,
                       string_agg(DISTINCT s.name, ', ') AS categories, min(c.id::text) AS category_id
                FROM partners p JOIN partner_capabilities pc ON pc.partner_id = p.id AND pc.status = 'APPROVED'
                JOIN subcategories s ON s.id = pc.subcategory_id AND s.active JOIN categories c ON c.id = s.category_id AND c.active
                WHERE p.registration_status = 'APPROVED' AND p.kyc_status = 'VERIFIED' AND c.vertical = 'MARKETPLACE'
                GROUP BY p.id ORDER BY p.rating_avg DESC NULLS LAST, p.rating_count DESC, p.display_name LIMIT :l""")
                .param("l", Math.min(Math.max(limit, 1), 30)).query().listOfRows();
    }

    /**
     * Universal search: categories, services/products and shops matching the text, plus ONE suggested workflow
     * (shop, request quotes, book a service, task, or personal assistance). The suggestion is advisory - a link.
     */
    public Map<String, Object> search(String q) {
        String text = q == null ? "" : q.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", text);
        if (text.length() < 2) {
            out.put("suggestion", null);
            out.put("categories", List.of());
            out.put("items", List.of());
            out.put("shops", List.of());
            return out;
        }
        String like = "%" + text + "%";
        List<Map<String, Object>> categories = jdbc.sql("""
                SELECT c.id, c.code, c.name, c.icon, c.vertical, c.workflow_template FROM categories c
                WHERE c.active AND""" + " " + CatalogueService.VERTICAL_ON + """
                   AND (c.name ILIKE :q OR c.description ILIKE :q OR EXISTS (SELECT 1 FROM subcategories s WHERE s.category_id = c.id AND s.active AND s.name ILIKE :q))
                ORDER BY (c.name ILIKE :p) DESC, c.sort_order LIMIT 6""").param("q", like).param("p", text + "%").query().listOfRows();
        List<Map<String, Object>> items = catalogue.search(text, 12);
        List<Map<String, Object>> shops = enabled("MARKETPLACE") ? jdbc.sql("""
                SELECT p.id, p.display_name, p.business_name, p.rating_avg, p.rating_count, string_agg(DISTINCT s.name, ', ') AS categories,
                       min(c.id::text) AS category_id
                FROM partners p JOIN partner_capabilities pc ON pc.partner_id = p.id AND pc.status = 'APPROVED'
                JOIN subcategories s ON s.id = pc.subcategory_id AND s.active JOIN categories c ON c.id = s.category_id AND c.active
                WHERE p.registration_status = 'APPROVED' AND p.kyc_status = 'VERIFIED' AND c.vertical = 'MARKETPLACE'
                  AND (p.display_name ILIKE :q OR p.business_name ILIKE :q OR s.name ILIKE :q OR c.name ILIKE :q
                       OR p.display_name ~* :rx OR s.name ~* :rx)
                GROUP BY p.id ORDER BY p.rating_avg DESC NULLS LAST LIMIT 5""").param("q", like)
                .param("rx", CatalogueService.searchTerms(text).isEmpty() ? "$^" : String.join("|", CatalogueService.searchTerms(text))).query().listOfRows() : List.of();
        out.put("suggestion", suggest(text, categories, items));
        out.put("categories", categories);
        out.put("items", items);
        out.put("shops", shops);
        return out;
    }

    /** Rule-based intent: keywords + what the catalogue matched. Falls back to personal assistance (a human helps). */
    Map<String, Object> suggest(String text, List<Map<String, Object>> categories, List<Map<String, Object>> items) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        // Keywords lead; what the catalogue matched only breaks ties (or decides when no keyword matched).
        int task = hits(t, TASK_WORDS) * 3, service = hits(t, SERVICE_WORDS) * 3, rfq = hits(t, RFQ_WORDS) * 3, buy = hits(t, BUY_WORDS) * 2,
                assist = hits(t, ASSIST_WORDS) * 3;
        if (BULK_QTY.matcher(t).find()) rfq += 2;
        String matched = !items.isEmpty() ? verticalOf(items.getFirst().get("category_id"))
                : !categories.isEmpty() ? String.valueOf(categories.getFirst().get("vertical")) : null;
        if (matched != null) {
            switch (matched) {
                case "MARKETPLACE" -> buy += 1;
                case "TASKS" -> task += 1;
                case "ASSISTANCE" -> assist += 1;
                default -> service += 1;
            }
        }
        record Option(String workflow, int score, String vertical) {}
        List<Option> options = new ArrayList<>(List.of(new Option("TASK", task, "TASKS"), new Option("BOOK_SERVICE", service, "SERVICES"),
                new Option("REQUEST_QUOTES", rfq + (buy > 0 ? 1 : 0), "MARKETPLACE"), new Option("SHOP", buy, "MARKETPLACE"),
                new Option("ASSISTANCE", assist, "ASSISTANCE")));
        options.removeIf(o -> !enabled(o.vertical()));
        Option best = options.stream().filter(o -> o.score() > 0).max((a, b) -> Integer.compare(a.score(), b.score())).orElse(null);
        String workflow = best == null ? "ASSISTANCE" : best.workflow();
        if (best == null && !enabled("ASSISTANCE")) return null;

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("workflow", workflow);
        Map<String, Object> target = targetFor(workflow, categories, items);
        s.putAll(target);
        s.put("title", switch (workflow) {
            case "TASK" -> "Request a task or errand";
            case "BOOK_SERVICE" -> "Book " + target.getOrDefault("label", "a service");
            case "REQUEST_QUOTES" -> "Get quotes from local shops";
            case "SHOP" -> "Shop " + target.getOrDefault("label", "from local shops");
            default -> "Ask a BUZZER agent to handle it";
        });
        s.put("description", switch (workflow) {
            case "TASK" -> "Pickups, drops, collections and errands near you.";
            case "BOOK_SERVICE" -> "Verified professionals - fixed prices or quotes.";
            case "REQUEST_QUOTES" -> "Send your list to verified shops and compare item-wise quotes.";
            case "SHOP" -> "Browse items and buy from local shops.";
            default -> "Describe what you need and a person takes it from there.";
        });
        return s;
    }

    private Map<String, Object> targetFor(String workflow, List<Map<String, Object>> categories, List<Map<String, Object>> items) {
        String vertical = switch (workflow) {
            case "TASK" -> "TASKS";
            case "BOOK_SERVICE" -> "SERVICES";
            case "ASSISTANCE" -> "ASSISTANCE";
            default -> "MARKETPLACE";
        };
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("vertical", vertical);
        for (Map<String, Object> i : items) {
            if (vertical.equals(verticalOf(i.get("category_id")))) {
                t.put("category_id", i.get("category_id"));
                t.put("subcategory_id", i.get("subcategory_id"));
                t.put("label", i.get("subcategory_name"));
                t.put("category_name", i.get("category_name"));
                return t;
            }
        }
        for (Map<String, Object> c : categories) {
            if (vertical.equals(c.get("vertical"))) {
                t.put("category_id", c.get("id"));
                t.put("label", c.get("name"));
                t.put("category_name", c.get("name"));
                return t;
            }
        }
        return t;
    }

    private String verticalOf(Object categoryId) {
        if (categoryId == null) return "SERVICES";
        return jdbc.sql("SELECT vertical FROM categories WHERE id = :c").param("c", categoryId).query(String.class).optional().orElse("SERVICES");
    }

    private static int hits(String text, List<String> words) {
        int n = 0;
        for (String w : words) if (text.contains(w)) n++;
        return n;
    }
}
