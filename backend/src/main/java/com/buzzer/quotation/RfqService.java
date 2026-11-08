package com.buzzer.quotation;

import com.buzzer.common.BusinessException;
import com.buzzer.common.Json;
import com.buzzer.request.RequestService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quotation requests (RFQ) for products: the customer lists items with preferred brand, specifications, quantity and unit
 * (plus photos / BOQ attachments), the request goes to 5-10 relevant shops automatically, shops quote item by item, and the
 * customer compares the quotes side by side and accepts ONE (which turns the request into an order).
 */
@Service
public class RfqService {
    public record RfqItem(String name, String brand, String specs, Integer quantity, String unit, String notes, UUID productId) {}

    public record RfqInput(UUID categoryId, UUID subcategoryId, UUID addressId, String description, List<RfqItem> items, String fulfilment,
                           Boolean bulk, OffsetDateTime neededBy) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final RequestService requests;
    private final DirectQuoteService directQuotes;

    public RfqService(JdbcClient jdbc, Json json, RequestService requests, DirectQuoteService directQuotes) {
        this.jdbc = jdbc;
        this.json = json;
        this.requests = requests;
        this.directQuotes = directQuotes;
    }

    @Transactional
    public RequestService.Created create(UUID customer, RfqInput in, String idempotencyKey) {
        if (in == null || in.categoryId() == null) throw BusinessException.invalid("Choose what kind of products you need");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) throw BusinessException.invalid("Idempotency-Key header is required");
        String vertical = jdbc.sql("SELECT vertical FROM categories WHERE id = :c").param("c", in.categoryId()).query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Category"));
        if (!"MARKETPLACE".equals(vertical)) throw BusinessException.invalid("Quotation requests are for products (Shop & Buy categories)");
        List<RfqItem> items = in.items() == null ? List.of() : in.items();
        if (items.isEmpty()) throw BusinessException.invalid("Add at least one item");
        if (items.size() > 50) throw BusinessException.invalid("Add at most 50 items per request");
        String fulfilment = in.fulfilment() == null ? (in.addressId() == null ? "PICKUP" : "DELIVERY") : in.fulfilment().trim().toUpperCase();
        if (!Set.of("PICKUP", "DELIVERY").contains(fulfilment)) throw BusinessException.invalid("Choose pickup or delivery");
        if ("DELIVERY".equals(fulfilment) && in.addressId() == null) throw BusinessException.invalid("Choose the delivery address");
        if (in.neededBy() != null && (in.neededBy().isBefore(OffsetDateTime.now().minusMinutes(5)) || in.neededBy().isAfter(OffsetDateTime.now().plusYears(1)))) {
            throw BusinessException.invalid("'Needed by' must be a date within the next year");
        }
        if (in.description() != null && in.description().length() > 2000) throw BusinessException.invalid("Description is too long");

        List<Map<String, Object>> lineItems = new ArrayList<>();
        List<RfqItem> clean = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        int no = 0;
        for (RfqItem it : items) {
            if (it == null) throw BusinessException.invalid("Empty item");
            String name = trim(it.name());
            if (it.productId() != null) {
                String productName = jdbc.sql("SELECT name FROM products WHERE id = :p AND status = 'ACTIVE'").param("p", it.productId())
                        .query(String.class).optional().orElseThrow(() -> BusinessException.invalid("Unknown product"));
                if (name == null) name = productName;
            }
            if (name == null || name.length() < 2 || name.length() > 200) throw BusinessException.invalid("Each item needs a name (2-200 characters)");
            int qty = it.quantity() == null ? 1 : it.quantity();
            if (qty < 1 || qty > 1000000) throw BusinessException.invalid("Quantity for '" + name + "' must be 1-1,000,000");
            check(it.brand(), 120, "Brand");
            check(it.specs(), 500, "Specifications");
            check(it.unit(), 40, "Unit");
            check(it.notes(), 300, "Notes");
            RfqItem c = new RfqItem(name, trim(it.brand()), trim(it.specs()), qty, trim(it.unit()), trim(it.notes()), it.productId());
            clean.add(c);
            Map<String, Object> li = new LinkedHashMap<>();
            li.put("key", "ri:" + (++no));
            li.put("custom", it.productId() == null);
            li.put("name", name);
            li.put("quantity", qty);
            li.put("unit", c.unit());
            li.put("pricing_type", "QUOTE_REQUIRED");
            li.put("unit_price", null);
            li.put("line_total", null);
            li.put("note", note(c));
            if (it.productId() != null) li.put("product_id", it.productId().toString());
            lineItems.add(li);
            if (!summary.isEmpty()) summary.append(", ");
            summary.append(qty).append(c.unit() == null ? " x " : " " + c.unit() + " ").append(name);
        }
        Map<String, Object> rfq = new LinkedHashMap<>();
        rfq.put("fulfilment", fulfilment);
        rfq.put("bulk", Boolean.TRUE.equals(in.bulk()));
        rfq.put("needed_by", in.neededBy() == null ? null : in.neededBy().toString());
        String description = (in.description() == null || in.description().isBlank() ? "" : in.description().trim() + "\n")
                + "Items: " + summary + (Boolean.TRUE.equals(in.bulk()) ? " (bulk)" : "");
        RequestService.Created c = requests.create(new RequestService.CreateCommand(customer, customer, "CUSTOMER_APP", in.categoryId(),
                in.subcategoryId(), null, in.addressId(), Map.of("rfq", rfq), description, in.neededBy(), null, "NORMAL", "rfq:" + idempotencyKey.trim(),
                null, "DIRECT_QUOTE", lineItems, null, true, true));
        if (c.duplicate()) return c;
        int line = 0;
        for (RfqItem it : clean) {
            line++;
            jdbc.sql("""
                    INSERT INTO request_items(request_id, line_key, line_no, product_id, name, brand_preference, specs, quantity, unit, notes)
                    VALUES (:r, :k, :no, :p, :n, :b, :s, :q, :u, :notes)""")
                    .param("r", c.id()).param("k", "ri:" + line).param("no", line).param("p", it.productId()).param("n", it.name()).param("b", it.brand())
                    .param("s", it.specs()).param("q", it.quantity()).param("u", it.unit()).param("notes", it.notes()).update();
        }
        directQuotes.dispatch(c.id());
        String status = jdbc.sql("SELECT status FROM service_requests WHERE id = :id").param("id", c.id()).query(String.class).single();
        return new RequestService.Created(c.id(), c.requestNumber(), status, false);
    }

    /** Cart "Get quotes" for Shop & Buy items: keep a structured copy of the lines so the accepted quote becomes an order. */
    public void saveItemsFromLines(UUID requestId) {
        String raw = jdbc.sql("SELECT line_items::text FROM service_requests WHERE id = :r").param("r", requestId).query(String.class).optional().orElse(null);
        var node = json.tree(raw);
        if (node == null || !node.isArray()) return;
        int no = 0;
        for (var l : node) {
            no++;
            String key = DirectQuoteService.key(l);
            UUID serviceItemId = l.path("service_item_id").asText("").isBlank() ? null : UUID.fromString(l.path("service_item_id").asText());
            UUID productId = serviceItemId == null ? null : jdbc.sql("SELECT id FROM products WHERE service_item_id = :s AND status = 'ACTIVE' LIMIT 1")
                    .param("s", serviceItemId).query(UUID.class).optional().orElse(null);
            String note = l.path("note").isMissingNode() || l.path("note").isNull() ? null : l.path("note").asText();
            jdbc.sql("""
                    INSERT INTO request_items(request_id, line_key, line_no, product_id, service_item_id, name, quantity, unit, notes)
                    VALUES (:r, :k, :no, :p, :s, :n, :q, :u, :notes) ON CONFLICT DO NOTHING""")
                    .param("r", requestId).param("k", key).param("no", no).param("p", productId).param("s", serviceItemId)
                    .param("n", l.path("name").asText("Item")).param("q", Math.max(1, l.path("quantity").asInt(1)))
                    .param("u", l.path("unit").isNull() || l.path("unit").isMissingNode() ? null : l.path("unit").asText())
                    .param("notes", note == null || note.length() <= 300 ? note : note.substring(0, 300)).update();
        }
    }

    /** Side-by-side comparison of every quote the customer can see: item by item, totals, lead time and validity. */
    public Map<String, Object> compare(UUID requestId) {
        List<Map<String, Object>> items = jdbc.sql("""
                SELECT line_key AS key, name, quantity, unit, brand_preference, specs, notes FROM request_items WHERE request_id = :r ORDER BY line_no""")
                .param("r", requestId).query().listOfRows();
        record Q(UUID id, String quotationNumber, UUID partnerId, String shopName, BigDecimal rating, String status, BigDecimal totalAmount,
                 BigDecimal deliveryFee, BigDecimal taxAmount, BigDecimal discountAmount, OffsetDateTime validUntil, boolean expired, Integer leadTimeDays,
                 String terms, String exclusions, String partnerNote, boolean payOnDeliveryAllowed, int revisionNo) {}
        List<Q> quotes = jdbc.sql("""
                SELECT q.id, q.quotation_number, q.partner_id, p.display_name AS shop_name, p.rating_avg AS rating, q.status, q.total_amount,
                       q.visit_fee AS delivery_fee, q.tax_amount, q.discount_amount, q.valid_until, (q.valid_until < now()) AS expired, q.lead_time_days,
                       q.terms, q.exclusions, q.partner_note, q.pay_on_delivery_allowed, q.revision_no
                FROM quotations q JOIN partners p ON p.id = q.partner_id
                WHERE q.request_id = :r AND q.status IN ('SHARED','ACCEPTED','REVISION_REQUESTED')
                ORDER BY q.total_amount""").param("r", requestId).query(Q.class).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Q q : quotes) {
            Map<String, Object> lines = new LinkedHashMap<>();
            int available = 0;
            Integer maxLead = q.leadTimeDays();
            for (Map<String, Object> l : jdbc.sql("""
                    SELECT line_key, brand, specs, available, unit_price, discount_percent, tax_rate, line_total, moq, lead_time_days, note
                    FROM quotation_items WHERE quotation_id = :q""").param("q", q.id()).query().listOfRows()) {
                lines.put((String) l.get("line_key"), l);
                if (Boolean.TRUE.equals(l.get("available"))) available++;
                Integer lead = (Integer) l.get("lead_time_days");
                if (lead != null && (maxLead == null || lead > maxLead)) maxLead = lead;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("quotation_id", q.id());
            m.put("quotation_number", q.quotationNumber());
            m.put("partner_id", q.partnerId());
            m.put("shop_name", q.shopName());
            m.put("rating", q.rating());
            m.put("status", q.status());
            m.put("total_amount", q.totalAmount());
            m.put("delivery_fee", q.deliveryFee());
            m.put("tax_amount", q.taxAmount());
            m.put("discount_amount", q.discountAmount());
            m.put("valid_until", q.validUntil());
            m.put("expired", q.expired());
            m.put("lead_time_days", maxLead);
            m.put("terms", q.terms());
            m.put("exclusions", q.exclusions());
            m.put("partner_note", q.partnerNote());
            m.put("pay_on_delivery_allowed", q.payOnDeliveryAllowed());
            m.put("revision_no", q.revisionNo());
            m.put("available_count", available);
            m.put("item_count", items.isEmpty() ? lines.size() : items.size());
            m.put("lines", lines);
            out.add(m);
        }
        List<Map<String, Object>> open = out.stream().filter(m -> "SHARED".equals(m.get("status")) && !Boolean.TRUE.equals(m.get("expired"))).toList();
        Map<String, Object> best = new LinkedHashMap<>();
        open.stream().filter(m -> m.get("available_count").equals(m.get("item_count")))
                .min(Comparator.comparing(m -> (BigDecimal) m.get("total_amount"))).ifPresent(m -> best.put("lowest_complete", m.get("quotation_id")));
        open.stream().min(Comparator.comparing(m -> (BigDecimal) m.get("total_amount"))).ifPresent(m -> best.put("lowest_total", m.get("quotation_id")));
        open.stream().max(Comparator.comparing(m -> (Integer) m.get("available_count"))).ifPresent(m -> best.put("most_items", m.get("quotation_id")));
        open.stream().filter(m -> m.get("lead_time_days") != null).min(Comparator.comparing(m -> (Integer) m.get("lead_time_days")))
                .ifPresent(m -> best.put("fastest", m.get("quotation_id")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("quotes", out);
        result.put("best", best);
        result.put("rule", "You can accept one shop's quote per request. The other shops are told you chose someone else.");
        return result;
    }

    private static String note(RfqItem it) {
        List<String> parts = new ArrayList<>();
        if (it.brand() != null) parts.add("Brand: " + it.brand());
        if (it.specs() != null) parts.add(it.specs());
        if (it.notes() != null) parts.add(it.notes());
        String s = String.join(" · ", parts);
        return s.isBlank() ? null : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    private static void check(String v, int max, String what) {
        if (v != null && v.length() > max) throw BusinessException.invalid(what + " must be " + max + " characters or less");
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
