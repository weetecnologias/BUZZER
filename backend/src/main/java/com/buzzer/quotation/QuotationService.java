package com.buzzer.quotation;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.SettingsService;
import com.buzzer.notification.NotificationService;
import com.buzzer.partner.PartnerEligibility;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Partner outreach and quotations. Many partners may quote one request; quoting is NOT assignment.
 */
@Service
public class QuotationService {
    public record QuoteInput(String scopeOfWork, BigDecimal labourAmount, BigDecimal materialsAmount, BigDecimal visitFee,
                             BigDecimal taxAmount, BigDecimal discountAmount, OffsetDateTime estimatedArrival,
                             OffsetDateTime estimatedCompletion, OffsetDateTime validUntil, String exclusions,
                             String cancellationTerms, String warranty, Boolean payOnDeliveryAllowed) {}

    private record Invitation(UUID id, UUID requestId, UUID partnerId, String status) {}

    private static final EnumSet<RequestStatus> QUOTABLE = EnumSet.of(RequestStatus.AGENT_REVIEW, RequestStatus.PARTNER_QUOTATION,
            RequestStatus.CUSTOMER_APPROVAL, RequestStatus.PARTNER_DECLINED, RequestStatus.NO_SHOW);

    private final JdbcClient jdbc;
    private final BusinessIds ids;
    private final RequestRepository requests;
    private final PartnerEligibility eligibility;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AuditService audit;

    public QuotationService(JdbcClient jdbc, BusinessIds ids, RequestRepository requests, PartnerEligibility eligibility,
                            NotificationService notifications, SettingsService settings, AuditService audit) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.requests = requests;
        this.eligibility = eligibility;
        this.notifications = notifications;
        this.settings = settings;
        this.audit = audit;
    }

    /** Agent invites eligible partners to quote. Ineligible partners are rejected, never silently skipped. */
    @Transactional
    public List<Map<String, Object>> invite(UUID requestId, List<UUID> partnerIds, UUID agentUserId) {
        if (partnerIds == null || partnerIds.isEmpty()) throw BusinessException.invalid("Select at least one partner");
        RequestRepository.Row r = requests.lock(requestId);
        if (!QUOTABLE.contains(r.statusEnum())) throw BusinessException.transition(r.status(), "PARTNER_QUOTATION");
        if (hasLiveAssignment(requestId)) throw new BusinessException(ErrorCode.REQUEST_ALREADY_ASSIGNED, "Request already assigned");
        List<Map<String, Object>> created = new ArrayList<>();
        for (UUID partnerId : partnerIds.stream().distinct().toList()) {
            if (!eligibility.isEligible(r, partnerId)) {
                throw new BusinessException(ErrorCode.PARTNER_NOT_ELIGIBLE, "Partner is not approved for this service/area",
                        Map.of("partnerId", partnerId));
            }
            String number = ids.next(BusinessIds.Kind.INVITATION);
            int inserted = jdbc.sql("""
                    INSERT INTO partner_invitations(invitation_number, request_id, partner_id, invited_by)
                    VALUES (:n, :r, :p, :by) ON CONFLICT (request_id, partner_id) DO NOTHING""")
                    .param("n", number).param("r", requestId).param("p", partnerId).param("by", agentUserId).update();
            if (inserted == 1) {
                created.add(Map.of("partner_id", partnerId, "invitation_number", number));
                UUID partnerUser = jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", partnerId).query(UUID.class).single();
                notifications.notifyUser(partnerUser, "New lead " + r.requestNumber(), "You have been invited to quote for a new requirement.",
                        "PARTNER_LEAD", "REQUEST", requestId);
            }
        }
        if (r.statusEnum() == RequestStatus.AGENT_REVIEW || r.statusEnum() == RequestStatus.PARTNER_DECLINED || r.statusEnum() == RequestStatus.NO_SHOW) {
            requests.transition(r, RequestStatus.PARTNER_QUOTATION, agentUserId, "AGENT", "Partners invited to quote");
        }
        jdbc.sql("UPDATE service_requests SET first_agent_response_at = COALESCE(first_agent_response_at, now()) WHERE id = :id")
                .param("id", requestId).update();
        return created;
    }

    @Transactional
    public Map<String, Object> submit(UUID invitationId, UUID partnerId, UUID partnerUserId, QuoteInput in) {
        Invitation inv = lockInvitation(invitationId, partnerId);
        if (!"INVITED".equals(inv.status())) throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "This lead is no longer open for quotes");
        RequestRepository.Row r = requests.lock(inv.requestId());
        if (!QUOTABLE.contains(r.statusEnum()) || hasLiveAssignment(r.id())) {
            throw new BusinessException(ErrorCode.REQUEST_ALREADY_ASSIGNED, "This request is no longer accepting quotations");
        }
        if (in.scopeOfWork() == null || in.scopeOfWork().trim().length() < 5) throw BusinessException.invalid("Describe the scope of work");
        BigDecimal labour = money(in.labourAmount()), materials = money(in.materialsAmount()), visit = money(in.visitFee()),
                tax = money(in.taxAmount()), discount = money(in.discountAmount());
        // Server computes the total; client totals are never trusted.
        BigDecimal total = labour.add(materials).add(visit).add(tax).subtract(discount).setScale(2, RoundingMode.HALF_UP);
        if (total.signum() <= 0) throw BusinessException.invalid("Total must be greater than zero");
        OffsetDateTime validUntil = in.validUntil() != null ? in.validUntil()
                : OffsetDateTime.now().plusHours(settings.getInt("quotation.default_validity_hours", 48));
        if (validUntil.isBefore(OffsetDateTime.now())) throw BusinessException.invalid("Validity must be in the future");
        boolean podAllowedBySvc = r.serviceItemId() == null || jdbc.sql("SELECT pay_on_delivery_allowed FROM service_items WHERE id = :s")
                .param("s", r.serviceItemId()).query(Boolean.class).single();
        String number = ids.next(BusinessIds.Kind.QUOTATION);
        UUID id = jdbc.sql("""
                INSERT INTO quotations(quotation_number, request_id, invitation_id, partner_id, scope_of_work, labour_amount,
                  materials_amount, visit_fee, tax_amount, discount_amount, total_amount, estimated_arrival, estimated_completion,
                  valid_until, exclusions, cancellation_terms, warranty, pay_on_delivery_allowed)
                VALUES (:n, :r, :i, :p, :scope, :lab, :mat, :visit, :tax, :disc, :total, :eta, :etc, :valid, :exc, :canc, :war, :pod)
                RETURNING id""")
                .param("n", number).param("r", r.id()).param("i", invitationId).param("p", partnerId).param("scope", in.scopeOfWork().trim())
                .param("lab", labour).param("mat", materials).param("visit", visit).param("tax", tax).param("disc", discount)
                .param("total", total).param("eta", in.estimatedArrival()).param("etc", in.estimatedCompletion()).param("valid", validUntil)
                .param("exc", in.exclusions()).param("canc", in.cancellationTerms()).param("war", in.warranty())
                .param("pod", podAllowedBySvc && Boolean.TRUE.equals(in.payOnDeliveryAllowed())).query(UUID.class).single();
        jdbc.sql("UPDATE partner_invitations SET status = 'QUOTED' WHERE id = :i").param("i", invitationId).update();
        audit.record(partnerUserId, "PARTNER", "QUOTATION_SUBMITTED", "QUOTATION", id, Map.of("total", total.toPlainString(), "request", r.requestNumber()));
        if (r.agentId() != null) {
            UUID agentUser = jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", r.agentId()).query(UUID.class).single();
            notifications.notifyUser(agentUser, "Quotation received for " + r.requestNumber(), "A partner submitted a quotation of Rs " + total.toPlainString(),
                    "AGENT_QUOTATION", "REQUEST", r.id());
        }
        return Map.of("id", id, "quotation_number", number, "total_amount", total);
    }

    @Transactional
    public void decline(UUID invitationId, UUID partnerId, String reason) {
        Invitation inv = lockInvitation(invitationId, partnerId);
        if (!"INVITED".equals(inv.status())) throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "This lead is no longer open");
        jdbc.sql("UPDATE partner_invitations SET status = 'DECLINED', decline_reason = :r, closed_at = now() WHERE id = :i")
                .param("r", reason).param("i", invitationId).update();
    }

    /** Agent presents selected quotations to the customer. */
    @Transactional
    public void share(UUID requestId, List<UUID> quotationIds, String agentNote, UUID agentUserId) {
        if (quotationIds == null || quotationIds.isEmpty()) throw BusinessException.invalid("Select at least one quotation");
        RequestRepository.Row r = requests.lock(requestId);
        if (!EnumSet.of(RequestStatus.PARTNER_QUOTATION, RequestStatus.CUSTOMER_APPROVAL).contains(r.statusEnum())) {
            throw BusinessException.transition(r.status(), "CUSTOMER_APPROVAL");
        }
        for (UUID q : quotationIds) {
            int n = jdbc.sql("""
                    UPDATE quotations SET status = 'SHARED', shared_at = now(), agent_note = COALESCE(:note, agent_note)
                    WHERE id = :q AND request_id = :r AND status IN ('SUBMITTED','SHARED') AND valid_until > now()""")
                    .param("note", agentNote).param("q", q).param("r", requestId).update();
            if (n == 0) throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "Quotation cannot be shared (expired or not found)", Map.of("quotationId", q));
        }
        requests.transition(r, RequestStatus.CUSTOMER_APPROVAL, agentUserId, "AGENT", quotationIds.size() + " quotation(s) shared with customer");
        notifications.notifyUser(r.customerUserId(), "Quotations ready for " + r.requestNumber(),
                "Your agent has shared " + quotationIds.size() + " quotation(s). Compare and choose the best option.",
                "CUSTOMER_QUOTATIONS", "REQUEST", requestId);
    }

    /** Customer rejects a shared quotation, optionally asking for clarification. */
    @Transactional
    public void customerReject(UUID requestId, UUID quotationId, UUID customerUserId, String reason, boolean clarification) {
        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customerUserId)) throw BusinessException.forbidden("Not your request");
        if (clarification) {
            requests.addMessage(requestId, customerUserId, "CUSTOMER", "Clarification requested on quotation: " + (reason == null ? "" : reason));
        } else {
            int n = jdbc.sql("UPDATE quotations SET status = 'REJECTED', decided_at = now() WHERE id = :q AND request_id = :r AND status = 'SHARED'")
                    .param("q", quotationId).param("r", requestId).update();
            if (n == 0) throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "Quotation is not open for a decision");
            Integer remaining = jdbc.sql("SELECT count(*) FROM quotations WHERE request_id = :r AND status = 'SHARED'").param("r", requestId)
                    .query(Integer.class).single();
            if (remaining == 0 && r.statusEnum() == RequestStatus.CUSTOMER_APPROVAL) {
                requests.transition(r, RequestStatus.PARTNER_QUOTATION, customerUserId, "CUSTOMER", "Customer rejected all shared quotations");
            }
        }
        if (r.agentId() != null) {
            UUID agentUser = jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", r.agentId()).query(UUID.class).single();
            notifications.notifyUser(agentUser, (clarification ? "Clarification requested on " : "Quotation rejected on ") + r.requestNumber(),
                    reason == null ? "" : reason, "AGENT_QUOTATION", "REQUEST", requestId);
        }
    }

    public List<Map<String, Object>> forRequest(UUID requestId, boolean customerView) {
        return jdbc.sql("""
                SELECT q.id, q.quotation_number, q.partner_id, p.display_name AS partner_name, p.partner_type, p.rating_avg, p.rating_count,
                       q.scope_of_work, q.labour_amount, q.materials_amount, q.visit_fee, q.tax_amount, q.discount_amount, q.total_amount,
                       q.currency, q.estimated_arrival, q.estimated_completion, q.valid_until, q.exclusions, q.cancellation_terms,
                       q.warranty, q.pay_on_delivery_allowed, q.status, q.agent_note, q.shared_at, q.created_at,
                       (q.valid_until < now()) AS expired
                FROM quotations q JOIN partners p ON p.id = q.partner_id
                WHERE q.request_id = :r AND (NOT :cv OR q.status IN ('SHARED','ACCEPTED','REJECTED'))
                ORDER BY q.total_amount""").param("r", requestId).param("cv", customerView).query().listOfRows();
    }

    public List<Map<String, Object>> invitations(UUID requestId) {
        return jdbc.sql("""
                SELECT i.id, i.invitation_number, i.status, i.decline_reason, i.created_at, p.id AS partner_id, p.display_name AS partner_name
                FROM partner_invitations i JOIN partners p ON p.id = i.partner_id WHERE i.request_id = :r ORDER BY i.created_at""")
                .param("r", requestId).query().listOfRows();
    }

    private boolean hasLiveAssignment(UUID requestId) {
        return jdbc.sql("SELECT count(*) FROM assignments WHERE request_id = :r AND status IN ('ACTIVE','CONFIRMED','IN_PROGRESS')")
                .param("r", requestId).query(Integer.class).single() > 0;
    }

    private Invitation lockInvitation(UUID invitationId, UUID partnerId) {
        Invitation inv = jdbc.sql("SELECT id, request_id, partner_id, status FROM partner_invitations WHERE id = :i FOR UPDATE")
                .param("i", invitationId).query(Invitation.class).optional().orElseThrow(() -> BusinessException.notFound("Lead"));
        if (!inv.partnerId().equals(partnerId)) throw BusinessException.notFound("Lead"); // never reveal other partners' leads
        return inv;
    }

    private static BigDecimal money(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        if (v.signum() < 0) throw BusinessException.invalid("Amounts cannot be negative");
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
