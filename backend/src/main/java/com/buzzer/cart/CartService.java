package com.buzzer.cart;

import com.buzzer.common.BusinessException;
import com.buzzer.request.RequestService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Customer cart for menu-priced services, plus wishlist and checkout.
 *
 * Prices are always computed on the server from the live catalogue (never from the client). Checkout turns the
 * cart into one service request per subcategory - each subcategory is fulfilled by a different kind of partner -
 * with a price snapshot of every line. The customer chooses how to proceed:
 *   SELF            "Book now at listed prices": the agent only assigns a verified partner at those prices;
 *   AGENT_ASSISTED  "Send to my agent": the agent reviews, suggests alternatives and collects quotations.
 */
@Service
public class CartService {
    /** Categories whose items go through the cart. Others (real estate, jobs, special requests) use requirement forms. */
    private static final Set<String> CART_WORKFLOWS = Set.of("MENU_DRIVEN", "EVENT_BRIEF", "CATEGORY_SPECIAL_REQUEST", "API_PRICED");
    private static final Set<String> ESTIMATE_TYPES = Set.of("STARTING_FROM", "VISIT_PLUS_LABOUR", "RANGE");

    public record CheckoutInput(String mode, UUID addressId, OffsetDateTime preferredStart, String note, String idempotencyKey) {}

    private record Line(UUID cartItemId, UUID serviceItemId, String name, int quantity, String pricingType, BigDecimal basePrice,
                        BigDecimal minPrice, BigDecimal maxPrice, BigDecimal originalPrice, String dealLabel, String unit,
                        String imageUrl, String icon, UUID subcategoryId, String subcategoryName, String subcategoryIcon,
                        UUID categoryId, String categoryName) {}

    private final JdbcClient jdbc;
    private final RequestService requests;

    public CartService(JdbcClient jdbc, RequestService requests) {
        this.jdbc = jdbc;
        this.requests = requests;
    }

    /** Unit price used for estimates; null means the item is priced by quotation. */
    static BigDecimal unitPrice(String pricingType, BigDecimal base, BigDecimal min) {
        return switch (pricingType) {
            case "QUOTE_REQUIRED", "API_PRICED" -> null;
            case "RANGE" -> min;
            default -> base;
        };
    }

    // ----- Cart -------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> add(UUID customer, UUID serviceItemId, int quantity) {
        if (quantity < 1 || quantity > 50) throw BusinessException.invalid("Quantity must be between 1 and 50");
        Map<String, Object> item = jdbc.sql("""
                SELECT i.id, i.is_other, c.workflow_template, i.active AND s.active AND c.active AS available
                FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                WHERE i.id = :i""").param("i", serviceItemId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("Service"));
        if (!Boolean.TRUE.equals(item.get("available"))) throw BusinessException.invalid("This service is not available right now");
        if (Boolean.TRUE.equals(item.get("is_other"))) {
            throw BusinessException.invalid("Describe this requirement using the request form instead of the cart");
        }
        if (!CART_WORKFLOWS.contains((String) item.get("workflow_template"))) {
            throw BusinessException.invalid("This service needs a requirement form - it cannot be added to the cart");
        }
        jdbc.sql("""
                INSERT INTO cart_items(customer_user_id, service_item_id, quantity) VALUES (:c, :i, :q)
                ON CONFLICT (customer_user_id, service_item_id)
                DO UPDATE SET quantity = LEAST(50, cart_items.quantity + EXCLUDED.quantity), updated_at = now()""")
                .param("c", customer).param("i", serviceItemId).param("q", quantity).update();
        return view(customer);
    }

    @Transactional
    public Map<String, Object> setQuantity(UUID customer, UUID serviceItemId, int quantity) {
        if (quantity > 50) throw BusinessException.invalid("Quantity must be at most 50");
        if (quantity <= 0) {
            jdbc.sql("DELETE FROM cart_items WHERE customer_user_id = :c AND service_item_id = :i").param("c", customer).param("i", serviceItemId).update();
        } else {
            int n = jdbc.sql("UPDATE cart_items SET quantity = :q, updated_at = now() WHERE customer_user_id = :c AND service_item_id = :i")
                    .param("q", quantity).param("c", customer).param("i", serviceItemId).update();
            if (n == 0) return add(customer, serviceItemId, quantity);
        }
        return view(customer);
    }

    public void clear(UUID customer) {
        jdbc.sql("DELETE FROM cart_items WHERE customer_user_id = :c").param("c", customer).update();
    }

    public Map<String, Object> view(UUID customer) {
        List<Line> lines = lines(customer);
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        boolean hasQuote = false;
        boolean estimated = false;
        int count = 0;
        for (Line l : lines) {
            BigDecimal unit = unitPrice(l.pricingType(), l.basePrice(), l.minPrice());
            BigDecimal lineTotal = unit == null ? null : unit.multiply(BigDecimal.valueOf(l.quantity())).setScale(2, RoundingMode.HALF_UP);
            if (lineTotal == null) hasQuote = true; else total = total.add(lineTotal);
            if (ESTIMATE_TYPES.contains(l.pricingType())) estimated = true;
            if (l.originalPrice() != null && unit != null && l.originalPrice().compareTo(unit) > 0) {
                savings = savings.add(l.originalPrice().subtract(unit).multiply(BigDecimal.valueOf(l.quantity())));
            }
            count += l.quantity();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service_item_id", l.serviceItemId());
            m.put("name", l.name());
            m.put("quantity", l.quantity());
            m.put("pricing_type", l.pricingType());
            m.put("unit", l.unit());
            m.put("unit_price", unit);
            m.put("original_price", l.originalPrice());
            m.put("deal_label", l.dealLabel());
            m.put("line_total", lineTotal);
            m.put("estimated", ESTIMATE_TYPES.contains(l.pricingType()));
            m.put("image_url", l.imageUrl());
            m.put("icon", l.icon());
            m.put("subcategory_id", l.subcategoryId());
            m.put("subcategory_name", l.subcategoryName());
            m.put("subcategory_icon", l.subcategoryIcon());
            m.put("category_id", l.categoryId());
            m.put("category_name", l.categoryName());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("item_count", count);
        out.put("estimated_total", total);
        out.put("savings", savings);
        out.put("has_quote_items", hasQuote);
        out.put("has_estimates", estimated);
        out.put("request_count", lines.stream().map(Line::subcategoryId).distinct().count());
        return out;
    }

    private List<Line> lines(UUID customer) {
        return jdbc.sql("""
                SELECT ci.id AS cart_item_id, i.id AS service_item_id, i.name, ci.quantity, i.pricing_type, i.base_price, i.min_price,
                       i.max_price, i.original_price, i.deal_label, i.unit, i.image_url, i.icon,
                       s.id AS subcategory_id, s.name AS subcategory_name, s.icon AS subcategory_icon, c.id AS category_id, c.name AS category_name
                FROM cart_items ci JOIN service_items i ON i.id = ci.service_item_id JOIN subcategories s ON s.id = i.subcategory_id
                JOIN categories c ON c.id = s.category_id
                WHERE ci.customer_user_id = :c AND i.active AND s.active AND c.active
                ORDER BY c.sort_order, s.sort_order, i.sort_order""").param("c", customer).query(Line.class).list();
    }

    // ----- Checkout ---------------------------------------------------------------------------------

    @Transactional
    public List<RequestService.Created> checkout(UUID customer, CheckoutInput in) {
        String mode = in.mode() == null ? "" : in.mode();
        if (!List.of("SELF", "AGENT_ASSISTED").contains(mode)) throw BusinessException.invalid("Choose 'Book now' or 'Send to my agent'");
        if (in.idempotencyKey() == null || in.idempotencyKey().isBlank() || in.idempotencyKey().length() > 80) {
            throw BusinessException.invalid("Idempotency-Key header is required");
        }
        if (in.note() != null && in.note().length() > 2000) throw BusinessException.invalid("Note is too long");
        // A retried checkout returns the requests already created for this key.
        List<RequestService.Created> previous = jdbc.sql("""
                SELECT id, request_number, status, TRUE AS duplicate FROM service_requests
                WHERE customer_user_id = :c AND idempotency_key LIKE :k ORDER BY idempotency_key""")
                .param("c", customer).param("k", in.idempotencyKey() + "#%").query(RequestService.Created.class).list();
        if (!previous.isEmpty()) return previous;

        List<Line> lines = lines(customer);
        if (lines.isEmpty()) throw BusinessException.invalid("Your cart is empty");
        if (in.addressId() == null) throw BusinessException.invalid("Choose a service address");

        Map<UUID, List<Line>> bySub = new LinkedHashMap<>();
        for (Line l : lines) bySub.computeIfAbsent(l.subcategoryId(), k -> new ArrayList<>()).add(l);

        List<RequestService.Created> created = new ArrayList<>();
        int index = 0;
        for (List<Line> group : bySub.values()) {
            List<Map<String, Object>> lineItems = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            boolean hasQuote = false;
            StringBuilder summary = new StringBuilder();
            for (Line l : group) {
                BigDecimal unit = unitPrice(l.pricingType(), l.basePrice(), l.minPrice());
                BigDecimal lineTotal = unit == null ? null : unit.multiply(BigDecimal.valueOf(l.quantity())).setScale(2, RoundingMode.HALF_UP);
                if (lineTotal == null) hasQuote = true; else total = total.add(lineTotal);
                Map<String, Object> li = new LinkedHashMap<>();
                li.put("service_item_id", l.serviceItemId().toString());
                li.put("name", l.name());
                li.put("quantity", l.quantity());
                li.put("unit", l.unit());
                li.put("pricing_type", l.pricingType());
                li.put("unit_price", unit);
                li.put("line_total", lineTotal);
                li.put("estimated", ESTIMATE_TYPES.contains(l.pricingType()));
                lineItems.add(li);
                if (!summary.isEmpty()) summary.append(", ");
                summary.append(l.quantity()).append(" x ").append(l.name());
            }
            Line first = group.getFirst();
            String description = summary + (in.note() == null || in.note().isBlank() ? "" : "\nNote: " + in.note().trim());
            created.add(requests.create(new RequestService.CreateCommand(customer, customer, "CUSTOMER_APP", first.categoryId(),
                    first.subcategoryId(), group.size() == 1 ? first.serviceItemId() : null, in.addressId(), Map.of(), description,
                    in.preferredStart(), null, "NORMAL", in.idempotencyKey() + "#" + (index++), null, mode, lineItems,
                    total.setScale(2, RoundingMode.HALF_UP), hasQuote, true)));
        }
        clear(customer);
        return created;
    }

    /** "Write to my agent": a free-text request that goes straight to the customer's dedicated agent. */
    @Transactional
    public RequestService.Created writeToAgent(UUID customer, String message, UUID categoryId, UUID addressId, OffsetDateTime preferredStart,
                                               String idempotencyKey) {
        if (message == null || message.trim().length() < 5) throw BusinessException.invalid("Tell your agent what you need (at least 5 characters)");
        if (message.length() > 2000) throw BusinessException.invalid("Message is too long");
        UUID category = categoryId != null ? categoryId
                : jdbc.sql("SELECT id FROM categories WHERE workflow_template = 'GLOBAL_SPECIAL_REQUEST' AND active ORDER BY sort_order LIMIT 1")
                .query(UUID.class).optional().orElseThrow(() -> BusinessException.notFound("Special requests category"));
        return requests.create(new RequestService.CreateCommand(customer, customer, "CUSTOMER_APP", category, null, null, addressId,
                Map.of("details", message.trim()), message.trim(), preferredStart, null, "NORMAL", idempotencyKey, null,
                "AGENT_DIRECT", null, null, true, true));
    }

    // ----- Wishlist ---------------------------------------------------------------------------------

    public void wish(UUID customer, UUID serviceItemId, boolean on) {
        if (on) {
            Integer exists = jdbc.sql("SELECT count(*) FROM service_items WHERE id = :i AND active").param("i", serviceItemId).query(Integer.class).single();
            if (exists == 0) throw BusinessException.notFound("Service");
            jdbc.sql("INSERT INTO wishlist_items(customer_user_id, service_item_id) VALUES (:c, :i) ON CONFLICT DO NOTHING")
                    .param("c", customer).param("i", serviceItemId).update();
        } else {
            jdbc.sql("DELETE FROM wishlist_items WHERE customer_user_id = :c AND service_item_id = :i").param("c", customer).param("i", serviceItemId).update();
        }
    }

    public List<Map<String, Object>> wishlist(UUID customer) {
        return jdbc.sql("""
                SELECT i.id, i.name, i.description, i.image_url, i.icon, i.pricing_type, i.base_price, i.min_price, i.max_price, i.unit,
                       i.original_price, i.deal_label, i.is_other, s.id AS subcategory_id, s.name AS subcategory_name, s.icon AS subcategory_icon,
                       c.id AS category_id, c.name AS category_name, c.workflow_template, w.created_at AS wished_at
                FROM wishlist_items w JOIN service_items i ON i.id = w.service_item_id JOIN subcategories s ON s.id = i.subcategory_id
                JOIN categories c ON c.id = s.category_id
                WHERE w.customer_user_id = :c AND i.active ORDER BY w.created_at DESC""").param("c", customer).query().listOfRows();
    }
}
