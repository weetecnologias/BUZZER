package com.buzzer.quotation;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import com.buzzer.notification.NotificationService;
import com.buzzer.partner.PartnerEligibility;
import com.buzzer.partner.PartnerPriceService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Inbuilt" quotations: an agent turns eligible partners' admin-approved price lists into quotations in one
 * tap, instead of waiting for each partner to quote manually. The quotation is marked source = PRICE_LIST and
 * the partner still confirms the booking before anything is fixed.
 */
@Service
public class InstantQuoteService {
    private static final EnumSet<RequestStatus> QUOTABLE = EnumSet.of(RequestStatus.REQUEST_CREATED, RequestStatus.AGENT_REVIEW, RequestStatus.PARTNER_QUOTATION,
            RequestStatus.CUSTOMER_APPROVAL, RequestStatus.PARTNER_DECLINED, RequestStatus.NO_SHOW);

    private final JdbcClient jdbc;
    private final Json json;
    private final BusinessIds ids;
    private final RequestRepository requests;
    private final PartnerEligibility eligibility;
    private final PartnerPriceService prices;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AuditService audit;

    public InstantQuoteService(JdbcClient jdbc, Json json, BusinessIds ids, RequestRepository requests, PartnerEligibility eligibility,
                               PartnerPriceService prices, NotificationService notifications, SettingsService settings, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.ids = ids;
        this.requests = requests;
        this.eligibility = eligibility;
        this.prices = prices;
        this.notifications = notifications;
        this.settings = settings;
        this.audit = audit;
    }

    /** The request's lines: cart line items, or the single requested service. */
    private List<Map<String, Object>> lines(UUID requestId) {
        record Row(String lineItems, UUID serviceItemId, String serviceName) {}
        Row r = jdbc.sql("""
                SELECT sr.line_items::text AS line_items, sr.service_item_id, i.name AS service_name
                FROM service_requests sr LEFT JOIN service_items i ON i.id = sr.service_item_id WHERE sr.id = :id""")
                .param("id", requestId).query(Row.class).single();
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode items = json.tree(r.lineItems());
        if (items != null && items.isArray() && !items.isEmpty()) {
            items.forEach(n -> out.add(Map.of("service_item_id", n.path("service_item_id").asText(), "name", n.path("name").asText(),
                    "quantity", n.path("quantity").asInt(1))));
        } else if (r.serviceItemId() != null) {
            out.add(Map.of("service_item_id", r.serviceItemId().toString(), "name", r.serviceName(), "quantity", 1));
        }
        return out;
    }

    /** Eligible partners with their price-list total for this request (complete or with missing lines). */
    public List<Map<String, Object>> options(RequestRepository.Row r) {
        List<Map<String, Object>> lines = lines(r.id());
        List<Map<String, Object>> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        for (Map<String, Object> p : eligibility.eligibleFor(r)) {
            Map<String, Object> q = new LinkedHashMap<>(prices.priceFor((UUID) p.get("id"), lines));
            q.put("partner_id", p.get("id"));
            q.put("partner_name", p.get("display_name"));
            q.put("rating_avg", p.get("rating_avg"));
            q.put("rating_count", p.get("rating_count"));
            q.put("already_invited", p.get("already_invited"));
            out.add(q);
        }
        out.sort((a, b) -> {
            BigDecimal ta = (BigDecimal) a.get("total"), tb = (BigDecimal) b.get("total");
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return ta.compareTo(tb);
        });
        return out;
    }

    @Transactional
    public List<Map<String, Object>> create(UUID requestId, List<UUID> partnerIds, UUID agentUserId) {
        if (partnerIds == null || partnerIds.isEmpty()) throw BusinessException.invalid("Select at least one partner");
        RequestRepository.Row r = requests.lock(requestId);
        if (!QUOTABLE.contains(r.statusEnum())) throw BusinessException.transition(r.status(), "PARTNER_QUOTATION");
        Integer live = jdbc.sql("SELECT count(*) FROM assignments WHERE request_id = :r AND status IN ('ACTIVE','CONFIRMED','IN_PROGRESS')")
                .param("r", requestId).query(Integer.class).single();
        if (live > 0) throw new BusinessException(ErrorCode.REQUEST_ALREADY_ASSIGNED, "Request already assigned");
        List<Map<String, Object>> lines = lines(requestId);
        if (lines.isEmpty()) throw BusinessException.invalid("This request has no menu items to price - collect quotations instead");
        boolean pod = jdbc.sql("""
                SELECT COALESCE(bool_and(pay_on_delivery_allowed), TRUE) FROM service_items WHERE id IN (:ids)""")
                .param("ids", lines.stream().map(l -> UUID.fromString(String.valueOf(l.get("service_item_id")))).toList()).query(Boolean.class).single();
        int hours = settings.getInt("quotation.default_validity_hours", 48);
        List<Map<String, Object>> created = new ArrayList<>();
        for (UUID partnerId : partnerIds.stream().distinct().toList()) {
            if (!eligibility.isEligible(r, partnerId)) {
                throw new BusinessException(ErrorCode.PARTNER_NOT_ELIGIBLE, "Partner is not approved for this service/area", Map.of("partnerId", partnerId));
            }
            Map<String, Object> price = prices.priceFor(partnerId, lines);
            if (!Boolean.TRUE.equals(price.get("complete"))) {
                throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "Partner has no approved price for: " + price.get("missing"),
                        Map.of("partnerId", partnerId));
            }
            Integer quoted = jdbc.sql("""
                    SELECT count(*) FROM quotations q JOIN partner_invitations i ON i.id = q.invitation_id
                    WHERE i.request_id = :r AND i.partner_id = :p AND q.status IN ('SUBMITTED','SHARED')""")
                    .param("r", requestId).param("p", partnerId).query(Integer.class).single();
            if (quoted > 0) continue; // already has an open quotation
            UUID invitationId = jdbc.sql("""
                    INSERT INTO partner_invitations(invitation_number, request_id, partner_id, invited_by, status)
                    VALUES (:n, :r, :p, :by, 'QUOTED')
                    ON CONFLICT (request_id, partner_id) DO UPDATE SET status = 'QUOTED' RETURNING id""")
                    .param("n", ids.next(BusinessIds.Kind.INVITATION)).param("r", requestId).param("p", partnerId).param("by", agentUserId)
                    .query(UUID.class).single();
            StringBuilder scope = new StringBuilder();
            for (Object o : (List<?>) price.get("breakdown")) {
                Map<?, ?> b = (Map<?, ?>) o;
                if (!scope.isEmpty()) scope.append("; ");
                scope.append(b.get("quantity")).append(" x ").append(b.get("name")).append(" @ Rs ").append(((BigDecimal) b.get("unit_price")).toPlainString());
            }
            String number = ids.next(BusinessIds.Kind.QUOTATION);
            BigDecimal total = (BigDecimal) price.get("total");
            UUID qid = jdbc.sql("""
                    INSERT INTO quotations(quotation_number, request_id, invitation_id, partner_id, scope_of_work, labour_amount, total_amount,
                      valid_until, pay_on_delivery_allowed, source, agent_note)
                    VALUES (:n, :r, :i, :p, :scope, :t, :t, :valid, :pod, 'PRICE_LIST', 'Generated from the partner''s approved price list')
                    RETURNING id""")
                    .param("n", number).param("r", requestId).param("i", invitationId).param("p", partnerId).param("scope", scope.toString())
                    .param("t", total).param("valid", OffsetDateTime.now().plusHours(hours)).param("pod", pod).query(UUID.class).single();
            created.add(Map.of("quotation_id", qid, "quotation_number", number, "partner_id", partnerId, "total_amount", total));
            UUID partnerUser = jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", partnerId).query(UUID.class).single();
            notifications.notifyUser(partnerUser, "Quotation from your price list - " + r.requestNumber(),
                    "Your agent quoted Rs " + total.toPlainString() + " using your approved prices. You'll confirm if the customer accepts.",
                    "PARTNER_LEAD", "REQUEST", requestId);
        }
        if (r.statusEnum() == RequestStatus.REQUEST_CREATED) {
            requests.transition(r, RequestStatus.AGENT_REVIEW, agentUserId, "AGENT", "Agent started review");
            r = requests.lock(requestId);
        }
        if (r.statusEnum() != RequestStatus.PARTNER_QUOTATION && r.statusEnum() != RequestStatus.CUSTOMER_APPROVAL) {
            requests.transition(r, RequestStatus.PARTNER_QUOTATION, agentUserId, "AGENT", "Instant quotations from partner price lists");
        }
        jdbc.sql("UPDATE service_requests SET first_agent_response_at = COALESCE(first_agent_response_at, now()) WHERE id = :id").param("id", requestId).update();
        audit.record(agentUserId, "AGENT", "INSTANT_QUOTES_CREATED", "REQUEST", requestId, Map.of("count", created.size()));
        return created;
    }
}
