package com.buzzer.quotation;

import com.buzzer.agent.AgentAllocationService;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import com.buzzer.notification.NotificationService;
import com.buzzer.ops.OpsExceptionService;
import com.buzzer.partner.PartnerEligibility;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Automated "Get quotes" flow - no manual agent work:
 * <ol>
 *   <li>Checkout sends the request straight to the best verified partners (price-list coverage, rating, workload).</li>
 *   <li>Each partner marks every item available / not available and prices it (pre-filled from their approved price list).</li>
 *   <li>The quote reaches the customer immediately; the customer approves and pays, or asks the partner to revise.</li>
 *   <li>If no partner is eligible, all decline, or nobody quotes in time, the request is handed to an agent automatically.</li>
 * </ol>
 * Every step is written to the request timeline with actor role SYSTEM so agents and admins can follow it.
 */
@Service
public class DirectQuoteService {
    private static final Logger log = LoggerFactory.getLogger(DirectQuoteService.class);
    private static final EnumSet<RequestStatus> QUOTABLE = EnumSet.of(RequestStatus.PARTNER_QUOTATION, RequestStatus.CUSTOMER_APPROVAL);
    private static final BigDecimal MAX_PRICE = new BigDecimal("10000000");

    /**
     * One quoted line. Optional quotation-request fields: brand / specifications offered, discount %, tax (GST) %,
     * minimum order quantity and lead time. Unit price is before discount and tax.
     */
    public record LineQuote(String key, Boolean available, BigDecimal unitPrice, String note, String brand, String specs,
                            BigDecimal discountPercent, BigDecimal taxPercent, Integer moq, Integer leadTimeDays) {
        public LineQuote(String key, Boolean available, BigDecimal unitPrice, String note) {
            this(key, available, unitPrice, note, null, null, null, null, null, null);
        }
    }

    public record ItemQuoteInput(List<LineQuote> lines, BigDecimal deliveryFee, OffsetDateTime estimatedArrival, String partnerNote,
                                 Boolean payOnDeliveryAllowed, Integer validHours, String terms, String exclusions, Integer leadTimeDays) {
        public ItemQuoteInput(List<LineQuote> lines, BigDecimal deliveryFee, OffsetDateTime estimatedArrival, String partnerNote,
                              Boolean payOnDeliveryAllowed, Integer validHours) {
            this(lines, deliveryFee, estimatedArrival, partnerNote, payOnDeliveryAllowed, validHours, null, null, null);
        }
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final BusinessIds ids;
    private final RequestRepository requests;
    private final PartnerEligibility eligibility;
    private final AgentAllocationService allocation;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final OpsExceptionService ops;
    private final AuditService audit;
    private final com.buzzer.request.AttachmentService attachments;
    private final RfqVendorSelector rfqVendors;
    @org.springframework.beans.factory.annotation.Autowired
    private com.buzzer.ops.SlaPolicy slaPolicy;

    public DirectQuoteService(JdbcClient jdbc, Json json, BusinessIds ids, RequestRepository requests, PartnerEligibility eligibility,
                              AgentAllocationService allocation, NotificationService notifications, SettingsService settings,
                              OpsExceptionService ops, AuditService audit, com.buzzer.request.AttachmentService attachments,
                              RfqVendorSelector rfqVendors) {
        this.rfqVendors = rfqVendors;
        this.attachments = attachments;
        this.jdbc = jdbc;
        this.json = json;
        this.ids = ids;
        this.requests = requests;
        this.eligibility = eligibility;
        this.allocation = allocation;
        this.notifications = notifications;
        this.settings = settings;
        this.ops = ops;
        this.audit = audit;
    }

    // ----- 1. Dispatch ------------------------------------------------------------------------------------

    /** Sends a freshly created DIRECT_QUOTE request to the best eligible partners. Same transaction as checkout. */
    @Transactional
    public void dispatch(UUID requestId) {
        RequestRepository.Row r = requests.lock(requestId);
        if (r.statusEnum() != RequestStatus.REQUEST_CREATED) return; // idempotent
        List<String> itemIds = lineItems(requestId).stream().map(l -> l.path("service_item_id").asText(""))
                .filter(s -> !s.isBlank()).toList();
        List<Map<String, Object>> eligible = new ArrayList<>(eligibility.eligibleFor(r));
        boolean rfq = "PRODUCT_RFQ".equals(jdbc.sql("SELECT request_type FROM service_requests WHERE id = :r").param("r", requestId)
                .query(String.class).single());
        // Quotation requests go to shops only (shop role, inside their service radius), ranked for bulk / distance.
        if (rfq) eligible = new ArrayList<>(rfqVendors.rank(r, eligible, rfqVendors.isBulk(requestId)));
        if (eligible.isEmpty()) {
            handToAgent(r, rfq ? "No verified shop near the customer can quote for these items yet" : "No verified partner is available for these items yet");
            return;
        }
        // Partners who already have approved prices for more of the requested items come first.
        Map<Object, Integer> coverage = new LinkedHashMap<>();
        for (Map<String, Object> p : eligible) {
            coverage.put(p.get("id"), itemIds.isEmpty() ? 0 : jdbc.sql("""
                    SELECT count(*) FROM partner_price_items WHERE partner_id = :p AND active AND status = 'APPROVED'
                      AND service_item_id::text IN (:items)""").param("p", p.get("id")).param("items", itemIds).query(Integer.class).single());
        }
        if (!rfq) eligible.sort(Comparator.comparing((Map<String, Object> p) -> -coverage.get(p.get("id")))); // stable: keeps rating/workload order
        int max = rfq ? rfqVendors.inviteCount(r) : Math.max(1, settings.getInt("direct_quote.max_partners", 5));
        List<Map<String, Object>> chosen = eligible.stream().limit(max).toList();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> p : chosen) {
            UUID partnerId = (UUID) p.get("id");
            jdbc.sql("""
                    INSERT INTO partner_invitations(invitation_number, request_id, partner_id, invited_by)
                    VALUES (:n, :r, :p, :by) ON CONFLICT (request_id, partner_id) DO NOTHING""")
                    .param("n", ids.next(BusinessIds.Kind.INVITATION)).param("r", requestId).param("p", partnerId)
                    .param("by", r.customerUserId()).update();
            names.add(String.valueOf(p.get("display_name")));
            UUID partnerUser = jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", partnerId).query(UUID.class).single();
            notifications.notifyUser(partnerUser, "New quote request " + r.requestNumber(),
                    itemIds.isEmpty() ? "A customer is asking for your price." : "A customer is asking for availability and price of "
                            + lineItems(requestId).size() + " item(s). Reply fast to win the order.", "PARTNER_LEAD", "REQUEST", requestId);
        }
        requests.transition(r, RequestStatus.PARTNER_QUOTATION, null, "SYSTEM",
                "Sent automatically to " + chosen.size() + " verified partner(s): " + String.join(", ", names));
    }

    // ----- 2. Partner quotes per item ---------------------------------------------------------------------

    /** What a partner sees for a lead: each item with a suggested price, plus their latest quote and any revision request. */
    public Map<String, Object> leadDetail(UUID invitationId, UUID partnerId) {
        Map<String, Object> lead = jdbc.sql("""
                SELECT i.id AS invitation_id, i.status AS invitation_status, i.created_at AS invited_at, r.id AS request_id, r.request_number,
                       r.status AS request_status, r.handling_mode, r.booking_mode, r.description, r.preferred_start, r.line_items::text AS line_items,
                       c.name AS category_name, s.name AS subcategory_name, l.name AS locality_name, r.estimated_total
                FROM partner_invitations i JOIN service_requests r ON r.id = i.request_id JOIN categories c ON c.id = r.category_id
                LEFT JOIN subcategories s ON s.id = r.subcategory_id LEFT JOIN localities l ON l.id = r.locality_id
                WHERE i.id = :i AND i.partner_id = :p""").param("i", invitationId).param("p", partnerId).query().listOfRows()
                .stream().findFirst().orElseThrow(() -> BusinessException.notFound("Lead"));
        UUID requestId = (UUID) lead.get("request_id");
        Map<String, BigDecimal> myPrices = new LinkedHashMap<>();
        jdbc.sql("SELECT service_item_id::text AS id, approved_price FROM partner_price_items WHERE partner_id = :p AND active AND status = 'APPROVED'")
                .param("p", partnerId).query().listOfRows().forEach(m -> myPrices.put((String) m.get("id"), (BigDecimal) m.get("approved_price")));
        Map<String, Map<String, Object>> requested = new LinkedHashMap<>();
        jdbc.sql("SELECT line_key, brand_preference, specs, notes FROM request_items WHERE request_id = :r").param("r", requestId).query().listOfRows()
                .forEach(ri -> requested.put((String) ri.get("line_key"), ri));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode l : lineItems(requestId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            String itemId = l.path("service_item_id").asText("");
            m.put("key", key(l));
            Map<String, Object> ri = requested.getOrDefault(key(l), Map.of());
            m.put("brand_preference", ri.get("brand_preference"));
            m.put("specs", ri.get("specs"));
            m.put("name", l.path("name").asText());
            m.put("quantity", l.path("quantity").asInt(1));
            m.put("unit", l.path("unit").isMissingNode() || l.path("unit").isNull() ? null : l.path("unit").asText());
            m.put("custom", l.path("custom").asBoolean(false));
            m.put("customer_note", l.path("note").isMissingNode() || l.path("note").isNull() ? null : l.path("note").asText());
            m.put("listed_price", l.path("unit_price").isNumber() ? l.path("unit_price").decimalValue() : null);
            m.put("my_price", myPrices.get(itemId));
            m.put("suggested_price", myPrices.getOrDefault(itemId, l.path("unit_price").isNumber() ? l.path("unit_price").decimalValue() : null));
            lines.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>(lead);
        out.put("line_items", lines);
        out.put("request_type", jdbc.sql("SELECT request_type FROM service_requests WHERE id = :r").param("r", requestId).query(String.class).single());
        String answers = jdbc.sql("SELECT answers::text FROM service_requests WHERE id = :r").param("r", requestId).query(String.class).optional().orElse(null);
        out.put("rfq", json.map(answers).get("rfq"));
        out.put("my_quote", jdbc.sql("""
                SELECT q.id, q.quotation_number, q.status, q.total_amount, q.visit_fee AS delivery_fee, q.line_items::text AS line_items,
                       q.revision_no, q.revision_note, q.partner_note, q.estimated_arrival, q.pay_on_delivery_allowed, q.created_at,
                       q.terms, q.exclusions, q.lead_time_days, q.tax_amount, q.discount_amount, q.valid_until
                FROM quotations q WHERE q.invitation_id = :i AND q.status <> 'REVISED' ORDER BY q.created_at DESC LIMIT 1""")
                .param("i", invitationId).query().listOfRows().stream().findFirst().map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>(q);
                    m.put("line_items", q.get("line_items") == null ? null : json.tree((String) q.get("line_items")));
                    return m;
                }).orElse(null));
        out.put("attachments", attachments.list(requestId, true));
        out.put("can_quote", QUOTABLE.stream().anyMatch(s -> s.name().equals(lead.get("request_status")))
                && List.of("INVITED", "QUOTED").contains((String) lead.get("invitation_status")));
        return out;
    }

    /** Partner submits (or revises) an item-by-item quote. Automated requests share it with the customer at once. */
    @Transactional
    public Map<String, Object> submitItemQuote(UUID invitationId, UUID partnerId, UUID partnerUserId, ItemQuoteInput in) {
        record Inv(UUID id, UUID requestId, UUID partnerId, String status) {}
        Inv inv = jdbc.sql("SELECT id, request_id, partner_id, status FROM partner_invitations WHERE id = :i FOR UPDATE")
                .param("i", invitationId).query(Inv.class).optional().orElseThrow(() -> BusinessException.notFound("Lead"));
        if (!inv.partnerId().equals(partnerId)) throw BusinessException.notFound("Lead");
        if (!List.of("INVITED", "QUOTED").contains(inv.status())) {
            throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "This lead is no longer open for quotes");
        }
        RequestRepository.Row r = requests.lock(inv.requestId());
        if (!QUOTABLE.contains(r.statusEnum()) || hasLiveAssignment(r.id())) {
            throw new BusinessException(ErrorCode.REQUEST_ALREADY_ASSIGNED, "This request is no longer accepting quotations");
        }
        List<JsonNode> requested = lineItems(r.id());
        if (requested.isEmpty()) throw BusinessException.invalid("This request has no item list - use the standard quotation form");
        if (in == null || in.lines() == null) throw BusinessException.invalid("Answer every item");
        Map<String, LineQuote> answers = new LinkedHashMap<>();
        for (LineQuote q : in.lines()) if (q != null && q.key() != null) answers.put(q.key(), q);

        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        int available = 0;
        for (JsonNode l : requested) {
            String k = key(l);
            LineQuote a = answers.get(k);
            if (a == null || a.available() == null) throw BusinessException.invalid("Mark '" + l.path("name").asText() + "' as available or not available");
            int qty = l.path("quantity").asInt(1);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            m.put("name", l.path("name").asText());
            m.put("quantity", qty);
            m.put("unit", l.path("unit").isNull() || l.path("unit").isMissingNode() ? null : l.path("unit").asText());
            m.put("custom", l.path("custom").asBoolean(false));
            m.put("available", a.available());
            if (a.note() != null && a.note().length() > 200) throw BusinessException.invalid("Item notes must be 200 characters or less");
            m.put("note", a.note() == null || a.note().isBlank() ? null : a.note().trim());
            if (a.brand() != null && a.brand().length() > 120) throw BusinessException.invalid("Brand must be 120 characters or less");
            if (a.specs() != null && a.specs().length() > 500) throw BusinessException.invalid("Specifications must be 500 characters or less");
            m.put("brand", a.brand() == null || a.brand().isBlank() ? null : a.brand().trim());
            m.put("specs", a.specs() == null || a.specs().isBlank() ? null : a.specs().trim());
            if (a.available()) {
                if (a.unitPrice() == null || a.unitPrice().signum() <= 0 || a.unitPrice().compareTo(MAX_PRICE) > 0) {
                    throw BusinessException.invalid("Enter a price for '" + l.path("name").asText() + "'");
                }
                BigDecimal discPct = a.discountPercent() == null ? BigDecimal.ZERO : a.discountPercent();
                BigDecimal taxPct = a.taxPercent() == null ? BigDecimal.ZERO : a.taxPercent();
                if (discPct.signum() < 0 || discPct.compareTo(BigDecimal.valueOf(100)) >= 0) throw BusinessException.invalid("Discount must be 0-99%");
                if (taxPct.signum() < 0 || taxPct.compareTo(BigDecimal.valueOf(40)) > 0) throw BusinessException.invalid("Tax must be 0-40%");
                if (a.moq() != null && (a.moq() < 1 || a.moq() > 1000000)) throw BusinessException.invalid("Minimum order quantity is invalid");
                if (a.leadTimeDays() != null && (a.leadTimeDays() < 0 || a.leadTimeDays() > 180)) throw BusinessException.invalid("Lead time must be 0-180 days");
                BigDecimal unit = a.unitPrice().setScale(2, RoundingMode.HALF_UP);
                BigDecimal gross = unit.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal discount = gross.multiply(discPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal tax = gross.subtract(discount).multiply(taxPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal lineTotal = gross.subtract(discount).add(tax).setScale(2, RoundingMode.HALF_UP);
                m.put("unit_price", unit);
                m.put("discount_percent", discPct);
                m.put("discount_amount", discount);
                m.put("tax_rate", taxPct);
                m.put("tax_amount", tax);
                m.put("moq", a.moq());
                m.put("lead_time_days", a.leadTimeDays());
                m.put("line_total", lineTotal);
                subtotal = subtotal.add(lineTotal);
                discountTotal = discountTotal.add(discount);
                taxTotal = taxTotal.add(tax);
                available++;
            } else {
                m.put("unit_price", null);
                m.put("line_total", null);
            }
            lines.add(m);
        }
        if (available == 0) throw BusinessException.invalid("At least one item must be available - otherwise decline the lead");
        BigDecimal delivery = in.deliveryFee() == null ? BigDecimal.ZERO : in.deliveryFee().setScale(2, RoundingMode.HALF_UP);
        if (delivery.signum() < 0 || delivery.compareTo(MAX_PRICE) > 0) throw BusinessException.invalid("Delivery fee is invalid");
        if (in.partnerNote() != null && in.partnerNote().length() > 500) throw BusinessException.invalid("Note must be 500 characters or less");
        if (in.terms() != null && in.terms().length() > 1000) throw BusinessException.invalid("Terms must be 1000 characters or less");
        if (in.exclusions() != null && in.exclusions().length() > 1000) throw BusinessException.invalid("Exclusions must be 1000 characters or less");
        if (in.leadTimeDays() != null && (in.leadTimeDays() < 0 || in.leadTimeDays() > 180)) throw BusinessException.invalid("Lead time must be 0-180 days");
        BigDecimal total = subtotal.add(delivery).setScale(2, RoundingMode.HALF_UP);
        int hours = in.validHours() == null ? settings.getInt("quotation.default_validity_hours", 48) : Math.min(Math.max(in.validHours(), 1), 168);

        // A new quote from the same partner replaces their previous one (revision history is kept).
        record Prev(UUID id, String status, int revisionNo) {}
        Prev prev = jdbc.sql("""
                SELECT id, status, revision_no FROM quotations WHERE invitation_id = :i AND status IN ('SUBMITTED','SHARED','REVISION_REQUESTED','ACCEPTED')
                ORDER BY created_at DESC LIMIT 1""").param("i", invitationId).query(Prev.class).optional().orElse(null);
        if (prev != null && "ACCEPTED".equals(prev.status())) throw BusinessException.invalid("The customer already accepted your quote");
        if (prev != null) jdbc.sql("UPDATE quotations SET status = 'REVISED', decided_at = now() WHERE id = :q").param("q", prev.id()).update();
        int revision = prev == null ? 1 : prev.revisionNo() + 1;

        boolean automated = "AUTOMATED".equals(handling(r.id()));
        boolean podBySvc = r.serviceItemId() == null || jdbc.sql("SELECT pay_on_delivery_allowed FROM service_items WHERE id = :s")
                .param("s", r.serviceItemId()).query(Boolean.class).single();
        String scope = available + " of " + requested.size() + " item(s) available" + (delivery.signum() > 0 ? " + delivery" : "")
                + (in.partnerNote() == null || in.partnerNote().isBlank() ? "" : ". " + in.partnerNote().trim());
        String number = ids.next(BusinessIds.Kind.QUOTATION);
        UUID id = jdbc.sql("""
                INSERT INTO quotations(quotation_number, request_id, invitation_id, partner_id, scope_of_work, labour_amount, materials_amount,
                  visit_fee, tax_amount, discount_amount, total_amount, estimated_arrival, valid_until, pay_on_delivery_allowed, status, shared_at,
                  line_items, revision_no, partner_note, terms, exclusions, lead_time_days)
                VALUES (:n, :r, :i, :p, :scope, 0, :sub, :del, :tax, :disc, :total, :eta, now() + make_interval(hours => :h), :pod, :st,
                  CASE WHEN :auto THEN now() END, CAST(:lines AS jsonb), :rev, :note, :terms, :excl, :lead)
                RETURNING id""")
                .param("n", number).param("r", r.id()).param("i", invitationId).param("p", partnerId).param("scope", scope)
                .param("sub", subtotal.subtract(taxTotal).setScale(2, RoundingMode.HALF_UP)).param("del", delivery)
                .param("tax", taxTotal.setScale(2, RoundingMode.HALF_UP)).param("disc", discountTotal.setScale(2, RoundingMode.HALF_UP)).param("total", total)
                .param("eta", in.estimatedArrival()).param("h", hours).param("pod", podBySvc && Boolean.TRUE.equals(in.payOnDeliveryAllowed()))
                .param("st", automated ? "SHARED" : "SUBMITTED").param("auto", automated).param("lines", json.write(lines))
                .param("rev", revision).param("note", in.partnerNote() == null || in.partnerNote().isBlank() ? null : in.partnerNote().trim())
                .param("terms", in.terms() == null || in.terms().isBlank() ? null : in.terms().trim())
                .param("excl", in.exclusions() == null || in.exclusions().isBlank() ? null : in.exclusions().trim()).param("lead", in.leadTimeDays())
                .query(UUID.class).single();
        // Structured copy of every line (used for comparison and for the order snapshot when this quote is accepted).
        for (Map<String, Object> m : lines) {
            jdbc.sql("""
                    INSERT INTO quotation_items(quotation_id, request_item_id, line_key, name, brand, specs, quantity, unit, available, unit_price,
                      discount_percent, discount_amount, tax_rate, tax_amount, line_total, moq, lead_time_days, note)
                    VALUES (:q, (SELECT id FROM request_items WHERE request_id = :r AND line_key = :k), :k, :n, :b, :sp, :qty, :u, :av, :up,
                      COALESCE(:dp, 0), COALESCE(:da, 0), COALESCE(:tr, 0), COALESCE(:ta, 0), :lt, :moq, :lead, :note)""")
                    .param("q", id).param("r", r.id()).param("k", m.get("key")).param("n", m.get("name")).param("b", m.get("brand"))
                    .param("sp", m.get("specs")).param("qty", m.get("quantity")).param("u", m.get("unit")).param("av", m.get("available"))
                    .param("up", m.get("unit_price")).param("dp", m.get("discount_percent")).param("da", m.get("discount_amount"))
                    .param("tr", m.get("tax_rate")).param("ta", m.get("tax_amount")).param("lt", m.get("line_total")).param("moq", m.get("moq"))
                    .param("lead", m.get("lead_time_days")).param("note", m.get("note")).update();
        }
        jdbc.sql("UPDATE partner_invitations SET status = 'QUOTED' WHERE id = :i").param("i", invitationId).update();
        String partnerName = jdbc.sql("SELECT display_name FROM partners WHERE id = :p").param("p", partnerId).query(String.class).single();
        audit.record(partnerUserId, "PARTNER", revision > 1 ? "QUOTATION_REVISED" : "QUOTATION_SUBMITTED", "QUOTATION", id,
                Map.of("total", total.toPlainString(), "request", r.requestNumber(), "available", available + "/" + requested.size()));

        String summary = (revision > 1 ? "Revised quote (v" + revision + ")" : "Quote") + " from " + partnerName + ": Rs " + total.toPlainString()
                + " - " + available + "/" + requested.size() + " items available";
        if (automated) {
            if (r.statusEnum() == RequestStatus.PARTNER_QUOTATION) {
                requests.transition(r, RequestStatus.CUSTOMER_APPROVAL, partnerUserId, "SYSTEM", summary + " (shared automatically)");
            } else {
                requests.note(r.id(), r.status(), partnerUserId, "SYSTEM", summary + " (shared automatically)");
            }
            notifications.notifyUser(r.customerUserId(), (revision > 1 ? "Updated quote for " : "New quote for ") + r.requestNumber(),
                    partnerName + " quoted Rs " + total.toPlainString() + " (" + available + " of " + requested.size() + " items available). Review and approve in the app.",
                    "CUSTOMER_QUOTATIONS", "REQUEST", r.id());
        } else {
            requests.note(r.id(), r.status(), partnerUserId, "PARTNER", summary);
            if (r.agentId() != null) {
                UUID agentUser = jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", r.agentId()).query(UUID.class).single();
                notifications.notifyUser(agentUser, "Quotation received for " + r.requestNumber(), summary, "AGENT_QUOTATION", "REQUEST", r.id());
            }
        }
        return Map.of("id", id, "quotation_number", number, "total_amount", total, "revision_no", revision,
                "status", automated ? "SHARED" : "SUBMITTED");
    }

    // ----- 3. Customer asks for a revision ------------------------------------------------------------------

    @Transactional
    public void requestRevision(UUID requestId, UUID quotationId, UUID customerUserId, String note) {
        if (note == null || note.trim().length() < 3) throw BusinessException.invalid("Tell the partner what to change");
        if (note.length() > 500) throw BusinessException.invalid("Keep the note under 500 characters");
        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customerUserId)) throw BusinessException.forbidden("Not your request");
        if (r.statusEnum() != RequestStatus.CUSTOMER_APPROVAL) throw BusinessException.transition(r.status(), "REVISION_REQUESTED");
        record Q(UUID partnerId, String status) {}
        Q q = jdbc.sql("SELECT partner_id, status FROM quotations WHERE id = :q AND request_id = :r FOR UPDATE")
                .param("q", quotationId).param("r", requestId).query(Q.class).optional().orElseThrow(() -> BusinessException.notFound("Quotation"));
        if (!"SHARED".equals(q.status())) throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "This quotation is not open for changes");
        jdbc.sql("UPDATE quotations SET status = 'REVISION_REQUESTED', revision_note = :n, decided_at = now() WHERE id = :q")
                .param("n", note.trim()).param("q", quotationId).update();
        record P(UUID userId, String name) {}
        P p = jdbc.sql("SELECT user_id, display_name AS name FROM partners WHERE id = :p").param("p", q.partnerId()).query(P.class).single();
        notifications.notifyUser(p.userId(), "Customer asked to revise your quote on " + r.requestNumber(), note.trim(), "PARTNER_LEAD", "REQUEST", requestId);
        Integer open = jdbc.sql("SELECT count(*) FROM quotations WHERE request_id = :r AND status = 'SHARED'").param("r", requestId).query(Integer.class).single();
        String reason = "Customer asked " + p.name() + " to revise: " + note.trim();
        if (open == 0) requests.transition(r, RequestStatus.PARTNER_QUOTATION, customerUserId, "CUSTOMER", reason);
        else requests.note(requestId, r.status(), customerUserId, "CUSTOMER", reason);
        audit.record(customerUserId, "CUSTOMER", "QUOTATION_REVISION_REQUESTED", "QUOTATION", quotationId, Map.of("note", note.trim()));
    }

    // ----- 4. Automatic fallback to an agent -----------------------------------------------------------------

    /** After a partner declines: if every invited partner declined, an agent takes over at once. */
    @Transactional
    public void afterDecline(UUID requestId) {
        RequestRepository.Row r = requests.lock(requestId);
        if (!"AUTOMATED".equals(handling(requestId)) || r.statusEnum() != RequestStatus.PARTNER_QUOTATION) return;
        Integer open = jdbc.sql("SELECT count(*) FROM partner_invitations WHERE request_id = :r AND status IN ('INVITED','QUOTED')")
                .param("r", requestId).query(Integer.class).single();
        if (open == 0) handToAgent(r, "All invited partners declined");
    }

    @Scheduled(fixedDelayString = "${buzzer.direct-quote.poll-ms:60000}", initialDelay = 30000)
    public void scheduledSweep() {
        try {
            int n = handOverUnquoted();
            if (n > 0) log.info("Handed {} unquoted direct request(s) to agents", n);
        } catch (Exception e) {
            log.warn("Direct-quote sweep failed: {}", e.getMessage());
        }
    }

    /** Automated requests without any quote after the configured time go to an agent. */
    @Transactional
    public int handOverUnquoted() {
        int minutes = settings.getInt("direct_quote.no_quote_minutes", 60);
        List<UUID> due = jdbc.sql("""
                SELECT r.id FROM service_requests r
                WHERE r.handling_mode = 'AUTOMATED' AND r.status = 'PARTNER_QUOTATION' AND r.created_at < now() - make_interval(mins => :m)
                  AND NOT EXISTS (SELECT 1 FROM quotations q WHERE q.request_id = r.id AND q.status IN ('SUBMITTED','SHARED','REVISION_REQUESTED'))
                ORDER BY r.created_at LIMIT 100 FOR UPDATE SKIP LOCKED""").param("m", minutes).query(UUID.class).list();
        for (UUID id : due) handToAgent(requests.lock(id), "No partner quoted within " + minutes + " minutes");
        return due.size();
    }

    /** Switches an automated request to agent handling: the subscriber's own agent, otherwise the on-duty agent. */
    private void handToAgent(RequestRepository.Row r, String reason) {
        UUID agentId = r.agentId();
        String source = null;
        if (agentId == null) {
            agentId = allocation.onDuty(r.customerUserId()).map(AgentAllocationService.Candidate::id).orElse(null);
            source = agentId == null ? null : "ON_DUTY";
        }
        int sla = slaPolicy.firstResponseMinutes(jdbc.sql("SELECT vertical FROM service_requests WHERE id = :r").param("r", r.id()).query(String.class).single(), r.priority());
        jdbc.sql("""
                UPDATE service_requests SET handling_mode = 'AGENT', agent_id = COALESCE(:a, agent_id), agent_source = COALESCE(:src, agent_source),
                  handed_to_agent_at = now(), handover_reason = :reason, agent_seen_at = NULL, first_agent_response_at = NULL,
                  sla_due_at = now() + make_interval(mins => :sla), updated_at = now()
                WHERE id = :id""").param("a", agentId).param("src", source).param("reason", reason).param("sla", sla).param("id", r.id()).update();
        String agentName = agentId == null ? null
                : jdbc.sql("SELECT display_name FROM agents WHERE id = :a").param("a", agentId).query(String.class).single();
        requests.note(r.id(), r.status(), null, "SYSTEM", reason + " - handed to " + (agentName == null ? "the operations team" : "agent " + agentName));
        ops.raise("DIRECT_QUOTE_HANDOVER", agentId == null ? "HIGH" : "MEDIUM", reason + " for " + r.requestNumber(), "REQUEST", r.id(),
                agentId == null ? "Assign an agent and source partners" : "Agent to source partners and quote", "direct-handover:" + r.id(), Map.of());
        if (agentId != null) {
            UUID agentUser = jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", agentId).query(UUID.class).single();
            notifications.notifyUser(agentUser, "Take over " + r.requestNumber(), reason + ". Please source partners and quote.",
                    "AGENT_NEW_REQUEST", "REQUEST", r.id());
        }
        notifications.notifyUser(r.customerUserId(), "We're on it - " + r.requestNumber(),
                agentName == null ? "Our team is finding the right partner for you." : agentName + " from BUZZER is now finding the right partner for you.",
                "REQUEST_UPDATE", "REQUEST", r.id());
    }

    /** Agent chooses to drive an automated request personally. */
    @Transactional
    public void takeOver(UUID requestId, UUID agentId, UUID agentUserId) {
        RequestRepository.Row r = requests.lock(requestId);
        if (!"AUTOMATED".equals(handling(requestId))) return;
        jdbc.sql("""
                UPDATE service_requests SET handling_mode = 'AGENT', agent_id = COALESCE(agent_id, :a), handed_to_agent_at = now(),
                  handover_reason = 'Agent took over', first_agent_response_at = COALESCE(first_agent_response_at, now()), updated_at = now()
                WHERE id = :id""").param("a", agentId).param("id", requestId).update();
        requests.note(requestId, r.status(), agentUserId, "AGENT", "Agent took over this automated request");
    }

    // ----- helpers --------------------------------------------------------------------------------------------

    private List<JsonNode> lineItems(UUID requestId) {
        String raw = jdbc.sql("SELECT line_items::text FROM service_requests WHERE id = :r").param("r", requestId).query(String.class).optional().orElse(null);
        JsonNode node = raw == null ? null : json.tree(raw);
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(out::add);
        return out;
    }

    /** Stable key for a request line: the explicit key (extras) or the menu item id. */
    static String key(JsonNode l) {
        String k = l.path("key").asText("");
        return k.isBlank() ? "item:" + l.path("service_item_id").asText() : k;
    }

    private String handling(UUID requestId) {
        return jdbc.sql("SELECT handling_mode FROM service_requests WHERE id = :r").param("r", requestId).query(String.class).single();
    }

    private boolean hasLiveAssignment(UUID requestId) {
        return jdbc.sql("SELECT count(*) FROM assignments WHERE request_id = :r AND status IN ('ACTIVE','CONFIRMED','IN_PROGRESS')")
                .param("r", requestId).query(Integer.class).single() > 0;
    }
}
