package com.buzzer.agent;

import com.buzzer.booking.BookingService;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import com.buzzer.marketplace.MarketplaceSql;
import com.buzzer.notification.NotificationService;
import com.buzzer.order.ProductCartService;
import com.buzzer.quotation.RfqService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent drafts (Vertical C rule): an agent prepares an RFQ, a one-shop product cart or a service booking for a customer in
 * their portfolio. Nothing is submitted, ordered, booked or paid until the CUSTOMER approves it in their own app - the
 * approval runs the normal customer flow under the customer's identity (and payment stays with the customer).
 */
@Service
public class DraftService {
    public record PurchaseLine(UUID listingId, Integer quantity) {}

    public record PurchaseDraft(List<PurchaseLine> items, String fulfilment, UUID addressId, String note) {}

    public record DraftInput(String kind, String title, String note, RfqService.RfqInput rfq, BookingService.BookInput booking, PurchaseDraft purchase) {}

    public record Decision(String paymentMethod, String note) {}

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a");

    private final JdbcClient jdbc;
    private final Json json;
    private final BusinessIds ids;
    private final SettingsService settings;
    private final AgentAccess access;
    private final AgentActivityService activity;
    private final RfqService rfqs;
    private final BookingService bookings;
    private final ProductCartService carts;
    private final RequestRepository requests;
    private final NotificationService notifications;
    private final AuditService audit;
    @org.springframework.beans.factory.annotation.Autowired
    private com.buzzer.catalogue.CatalogueService catalogue;
    @org.springframework.beans.factory.annotation.Autowired
    private com.buzzer.catalogue.FormSchemaValidator forms;

    public DraftService(JdbcClient jdbc, Json json, BusinessIds ids, SettingsService settings, AgentAccess access, AgentActivityService activity,
                        RfqService rfqs, BookingService bookings, ProductCartService carts, RequestRepository requests,
                        NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.ids = ids;
        this.settings = settings;
        this.access = access;
        this.activity = activity;
        this.rfqs = rfqs;
        this.bookings = bookings;
        this.carts = carts;
        this.requests = requests;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ===== Agent side ===============================================================================================

    @Transactional
    public Map<String, Object> create(AgentAccess.Me me, UUID customerUserId, DraftInput in) {
        access.requireCustomer(me, customerUserId);
        if (in == null || in.kind() == null) throw BusinessException.invalid("Choose what to prepare: RFQ, PURCHASE or BOOKING");
        String kind = in.kind().trim().toUpperCase();
        if (in.note() != null && in.note().length() > 1000) throw BusinessException.invalid("Note must be 1000 characters or less");
        if (in.title() != null && in.title().length() > 200) throw BusinessException.invalid("Title must be 200 characters or less");
        Object payload;
        String title;
        BigDecimal total = null;
        List<Map<String, Object>> preview = new ArrayList<>();
        switch (kind) {
            case "RFQ" -> {
                RfqService.RfqInput r = in.rfq();
                if (r == null || r.categoryId() == null) throw BusinessException.invalid("Choose the product category");
                String vertical = jdbc.sql("SELECT vertical FROM categories WHERE id = :c").param("c", r.categoryId()).query(String.class).optional()
                        .orElseThrow(() -> BusinessException.notFound("Category"));
                if (!"MARKETPLACE".equals(vertical)) throw BusinessException.invalid("Quotation requests are for Shop & Buy categories");
                if (r.items() == null || r.items().isEmpty() || r.items().size() > 50) throw BusinessException.invalid("Add 1-50 items");
                ownAddress(customerUserId, r.addressId());
                for (RfqService.RfqItem it : r.items()) {
                    if (it == null || it.name() == null || it.name().trim().length() < 2) throw BusinessException.invalid("Each item needs a name");
                    preview.add(line((it.quantity() == null ? 1 : it.quantity()) + (it.unit() == null ? " x " : " " + it.unit() + " ") + it.name().trim(),
                            join(it.brand() == null ? null : "Brand: " + it.brand(), it.specs()), null));
                }
                payload = r;
                title = "Quotation request: " + r.items().size() + " item(s)" + (Boolean.TRUE.equals(r.bulk()) ? " (bulk)" : "");
            }
            case "BOOKING" -> {
                BookingService.BookInput b = in.booking();
                if (b == null || b.serviceItemId() == null || b.partnerId() == null || b.slotStart() == null || b.addressId() == null) {
                    throw BusinessException.invalid("Choose the service, address, time and professional");
                }
                Map<String, Object> match = bookings.providers(customerUserId, b.serviceItemId(), b.addressId(), b.slotStart(),
                                b.quantity() == null ? 1 : b.quantity()).stream()
                        .filter(p -> b.partnerId().equals(p.get("id"))).findFirst()
                        .orElseThrow(() -> new BusinessException(ErrorCode.SLOT_UNAVAILABLE, "That professional is not free at that time"));
                // The service form is checked now, so the customer never approves something that would be refused.
                forms.validateAnswers(catalogue.resolve(null, null, b.serviceItemId()).formSchema(), b.answers());
                String service = jdbc.sql("SELECT name FROM service_items WHERE id = :s").param("s", b.serviceItemId()).query(String.class).single();
                String when = b.slotStart().atZoneSameInstant(ZoneId.of(settings.get("platform.timezone", "Asia/Kolkata"))).format(WHEN);
                total = (BigDecimal) match.get("total");
                preview.add(line(service, "With " + match.get("display_name") + " · " + when, total));
                payload = new BookingService.BookInput(b.serviceItemId(), b.partnerId(), b.slotStart(), b.addressId(), b.answers(), b.description(), null, b.quantity());
                title = service + " with " + match.get("display_name") + ", " + when;
            }
            case "PURCHASE" -> {
                PurchaseDraft p = in.purchase();
                if (p == null || p.items() == null || p.items().isEmpty() || p.items().size() > 50) throw BusinessException.invalid("Add 1-50 products");
                ownAddress(customerUserId, p.addressId());
                UUID shop = null;
                String shopName = null;
                total = BigDecimal.ZERO;
                for (PurchaseLine l : p.items()) {
                    if (l == null || l.listingId() == null) throw BusinessException.invalid("Choose a shop offer for every product");
                    int qty = l.quantity() == null ? 1 : l.quantity();
                    record L(UUID partnerId, String shopName, String productName, String variantName, BigDecimal price, BigDecimal bulkPrice,
                             Integer bulkMinQty, int moq, String stockStatus, boolean visible) {}
                    L x = jdbc.sql("SELECT p.id AS partner_id, p.display_name AS shop_name, pr.name AS product_name, v.name AS variant_name, l.price, l.bulk_price, "
                                    + "l.bulk_min_qty, l.moq, l.stock_status, (" + MarketplaceSql.VISIBLE_LISTING + ") AS visible "
                                    + MarketplaceSql.LISTING_JOINS + " WHERE l.id = :id")
                            .param("id", l.listingId()).query(L.class).optional().orElseThrow(() -> BusinessException.notFound("Product offer"));
                    if (!x.visible() || "OUT_OF_STOCK".equals(x.stockStatus())) throw new BusinessException(ErrorCode.ITEM_UNAVAILABLE, x.productName() + " is not available");
                    if (shop != null && !shop.equals(x.partnerId())) throw new BusinessException(ErrorCode.CART_VENDOR_MISMATCH, "A purchase draft is from one shop only");
                    if (qty < x.moq()) throw BusinessException.invalid("Minimum order for " + x.productName() + " is " + x.moq());
                    shop = x.partnerId();
                    shopName = x.shopName();
                    BigDecimal unit = x.bulkMinQty() != null && qty >= x.bulkMinQty() ? x.bulkPrice() : x.price();
                    BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                    total = total.add(lineTotal);
                    preview.add(line(qty + " x " + x.productName() + ("Standard".equals(x.variantName()) ? "" : " (" + x.variantName() + ")"),
                            "Rs " + unit.toPlainString() + " each at " + x.shopName(), lineTotal));
                }
                payload = p;
                title = p.items().size() + " product(s) from " + shopName;
            }
            default -> throw BusinessException.invalid("Kind must be RFQ, PURCHASE or BOOKING");
        }
        if (in.title() != null && !in.title().isBlank()) title = in.title().trim();
        int hours = Math.max(1, settings.getInt("drafts.valid_hours", 72));
        UUID id = jdbc.sql("""
                INSERT INTO agent_drafts(draft_number, customer_user_id, agent_id, created_by, kind, title, note, payload, preview, estimated_total, expires_at)
                VALUES (:n, :c, :a, :by, :k, :t, :note, CAST(:p AS jsonb), CAST(:pv AS jsonb), :total, now() + make_interval(hours => :h)) RETURNING id""")
                .param("n", ids.next(BusinessIds.Kind.DRAFT)).param("c", customerUserId).param("a", me.agentId()).param("by", me.userId())
                .param("k", kind).param("t", title).param("note", in.note() == null || in.note().isBlank() ? null : in.note().trim())
                .param("p", json.write(payload)).param("pv", json.write(preview)).param("total", total).param("h", hours).query(UUID.class).single();
        activity.log(me.agentId(), "DRAFT_SENT", "Sent for approval: " + title, customerUserId, null, null, id);
        notifications.notifyUser(customerUserId, "Your agent prepared something for you", me.displayName() + " prepared: " + title
                + ". Review and approve it in the app - nothing happens until you do.", "AGENT_DRAFT", "DRAFT", id);
        audit.record(me.userId(), "AGENT", "AGENT_DRAFT_SENT", "DRAFT", id, Map.of("kind", kind, "customer", customerUserId.toString()));
        return view(id);
    }

    public List<Map<String, Object>> forAgent(AgentAccess.Me me, String status, UUID customerUserId) {
        expire();
        return jdbc.sql(LIST + """
                WHERE (d.agent_id = :a OR (:mgr AND ag.manager_id = :a))
                  AND (CAST(:s AS text) IS NULL OR d.status = CAST(:s AS text)) AND (CAST(:c AS uuid) IS NULL OR d.customer_user_id = CAST(:c AS uuid))
                ORDER BY d.created_at DESC LIMIT 200""").param("a", me.agentId()).param("mgr", me.manager()).param("s", blank(status))
                .param("c", customerUserId).query().listOfRows().stream().map(this::decode).toList();
    }

    @Transactional
    public Map<String, Object> cancel(AgentAccess.Me me, UUID draftId) {
        record D(UUID agentId, UUID customerUserId, String status, String title) {}
        D d = jdbc.sql("SELECT agent_id, customer_user_id, status, title FROM agent_drafts WHERE id = :id FOR UPDATE").param("id", draftId).query(D.class)
                .optional().orElseThrow(() -> BusinessException.notFound("Draft"));
        if (!d.agentId().equals(me.agentId())) access.requireCustomer(me, d.customerUserId());
        if (!"SENT".equals(d.status())) throw BusinessException.transition(d.status(), "CANCELLED");
        jdbc.sql("UPDATE agent_drafts SET status = 'CANCELLED', decided_at = now(), updated_at = now() WHERE id = :id").param("id", draftId).update();
        activity.log(me.agentId(), "DRAFT_CANCELLED", "Withdrew: " + d.title(), d.customerUserId(), null, null, draftId);
        notifications.notifyUser(d.customerUserId(), "Draft withdrawn", me.displayName() + " withdrew: " + d.title(), "AGENT_DRAFT", "DRAFT", draftId);
        return view(draftId);
    }

    // ===== Customer side ==============================================================================================

    public List<Map<String, Object>> forCustomer(UUID customer, String status) {
        expire();
        return jdbc.sql(LIST + """
                WHERE d.customer_user_id = :c AND (CAST(:s AS text) IS NULL OR d.status = CAST(:s AS text))
                ORDER BY d.status = 'SENT' DESC, d.created_at DESC LIMIT 100""").param("c", customer).param("s", blank(status))
                .query().listOfRows().stream().map(this::decode).toList();
    }

    public Map<String, Object> customerView(UUID customer, UUID draftId) {
        expire();
        Map<String, Object> d = view(draftId);
        if (!customer.equals(d.get("customer_user_id"))) throw BusinessException.notFound("Draft");
        return d;
    }

    /** The customer's own approval: runs the normal customer flow (the customer submits, books or checks out). */
    @Transactional
    public Map<String, Object> approve(UUID customer, UUID draftId, Decision in) {
        expire();
        record D(UUID customerUserId, UUID agentId, String kind, String status, String title, String payload, String draftNumber) {}
        D d = jdbc.sql("SELECT customer_user_id, agent_id, kind, status, title, payload::text AS payload, draft_number FROM agent_drafts WHERE id = :id FOR UPDATE")
                .param("id", draftId).query(D.class).optional().orElseThrow(() -> BusinessException.notFound("Draft"));
        if (!d.customerUserId().equals(customer)) throw BusinessException.notFound("Draft");
        if (!"SENT".equals(d.status())) throw BusinessException.transition(d.status(), "APPROVED");
        String agentName = jdbc.sql("SELECT display_name FROM agents WHERE id = :a").param("a", d.agentId()).query(String.class).single();
        UUID requestId = null;
        String next;
        Object payment = null;
        switch (d.kind()) {
            case "RFQ" -> {
                RfqService.RfqInput r = json.mapper().convertValue(json.map(d.payload()), RfqService.RfqInput.class);
                RequestService.Created c = rfqs.create(customer, r, "draft-" + draftId);
                requestId = c.id();
                next = "REQUEST";
            }
            case "BOOKING" -> {
                BookingService.BookInput b = json.mapper().convertValue(json.map(d.payload()), BookingService.BookInput.class);
                String method = in == null || in.paymentMethod() == null || in.paymentMethod().isBlank() ? "PAY_ON_DELIVERY" : in.paymentMethod().toUpperCase();
                Map<String, Object> res = bookings.book(customer, new BookingService.BookInput(b.serviceItemId(), b.partnerId(), b.slotStart(), b.addressId(),
                        b.answers(), b.description(), method, b.quantity()), "draft-" + draftId);
                requestId = (UUID) res.get("id");
                payment = res.get("payment");
                next = "REQUEST";
            }
            case "PURCHASE" -> {
                PurchaseDraft p = json.mapper().convertValue(json.map(d.payload()), PurchaseDraft.class);
                boolean first = true;
                for (PurchaseLine l : p.items()) {
                    carts.add(customer, l.listingId(), l.quantity() == null ? 1 : l.quantity(), first);
                    first = false;
                }
                next = "PRODUCT_CART"; // the customer checks out (fulfilment, payment) themselves
            }
            default -> throw BusinessException.invalid("Unknown draft kind");
        }
        jdbc.sql("""
                UPDATE agent_drafts SET status = 'APPROVED', decided_at = now(), updated_at = now(), result_request_id = :r, decision_note = :n WHERE id = :id""")
                .param("r", requestId).param("n", in == null ? null : blank(in.note())).param("id", draftId).update();
        if (requestId != null) {
            String st = jdbc.sql("SELECT status FROM service_requests WHERE id = :r").param("r", requestId).query(String.class).single();
            requests.note(requestId, st, customer, "CUSTOMER", "Prepared by agent " + agentName + " (" + d.draftNumber() + ") and approved by the customer");
        }
        activity.log(d.agentId(), "DRAFT_APPROVED", "Customer approved: " + d.title(), customer, requestId, null, draftId);
        notifications.notifyUser(jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", d.agentId()).query(UUID.class).single(),
                "Approved by the customer", d.title() + ("PRODUCT_CART".equals(next) ? " - the customer is checking out" : ""), "AGENT_DRAFT", "DRAFT", draftId);
        audit.record(customer, "CUSTOMER", "AGENT_DRAFT_APPROVED", "DRAFT", draftId, Map.of("kind", d.kind()));
        Map<String, Object> out = new LinkedHashMap<>(view(draftId));
        out.put("next", next);
        out.put("payment", payment);
        return out;
    }

    @Transactional
    public Map<String, Object> reject(UUID customer, UUID draftId, Decision in) {
        record D(UUID customerUserId, UUID agentId, String status, String title) {}
        D d = jdbc.sql("SELECT customer_user_id, agent_id, status, title FROM agent_drafts WHERE id = :id FOR UPDATE").param("id", draftId)
                .query(D.class).optional().orElseThrow(() -> BusinessException.notFound("Draft"));
        if (!d.customerUserId().equals(customer)) throw BusinessException.notFound("Draft");
        if (!"SENT".equals(d.status())) throw BusinessException.transition(d.status(), "REJECTED");
        String note = in == null ? null : blank(in.note());
        if (note != null && note.length() > 500) throw BusinessException.invalid("Note must be 500 characters or less");
        jdbc.sql("UPDATE agent_drafts SET status = 'REJECTED', decided_at = now(), updated_at = now(), decision_note = :n WHERE id = :id")
                .param("n", note).param("id", draftId).update();
        activity.log(d.agentId(), "DRAFT_REJECTED", "Customer said no: " + d.title() + (note == null ? "" : " - " + note), customer, null, null, draftId);
        notifications.notifyUser(jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", d.agentId()).query(UUID.class).single(),
                "Not approved by the customer", d.title() + (note == null ? "" : ": " + note), "AGENT_DRAFT", "DRAFT", draftId);
        return view(draftId);
    }

    // ===== helpers ======================================================================================================

    private static final String LIST = """
            SELECT d.id, d.draft_number, d.kind, d.title, d.note, d.estimated_total, d.status, d.decision_note, d.result_request_id, r.request_number,
                   d.expires_at, d.decided_at, d.created_at, d.customer_user_id, cu.full_name AS customer_name, ag.display_name AS agent_name,
                   d.preview::text AS preview
            FROM agent_drafts d JOIN agents ag ON ag.id = d.agent_id JOIN users cu ON cu.id = d.customer_user_id
            LEFT JOIN service_requests r ON r.id = d.result_request_id
            """;

    private Map<String, Object> view(UUID id) {
        return jdbc.sql(LIST + " WHERE d.id = :id").param("id", id).query().listOfRows().stream().findFirst().map(this::decode)
                .orElseThrow(() -> BusinessException.notFound("Draft"));
    }

    private Map<String, Object> decode(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        m.put("preview", json.tree((String) row.get("preview")));
        return m;
    }

    private void expire() {
        jdbc.sql("UPDATE agent_drafts SET status = 'EXPIRED', updated_at = now() WHERE status = 'SENT' AND expires_at < now()").update();
    }

    private void ownAddress(UUID customer, UUID addressId) {
        if (addressId == null) return;
        Integer ok = jdbc.sql("SELECT count(*) FROM addresses WHERE id = :a AND user_id = :u AND deleted_at IS NULL").param("a", addressId).param("u", customer)
                .query(Integer.class).single();
        if (ok == 0) throw BusinessException.invalid("Address not found for this customer");
    }

    private static Map<String, Object> line(String label, String detail, BigDecimal amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("detail", detail);
        m.put("amount", amount);
        return m;
    }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p.trim());
        return out.isEmpty() ? null : String.join(" · ", out);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static final Set<String> KINDS = Set.of("RFQ", "PURCHASE", "BOOKING");
}
