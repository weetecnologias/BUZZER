package com.buzzer.assignment;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import com.buzzer.notification.NotificationService;
import com.buzzer.ops.OpsExceptionService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * EXCLUSIVE ASSIGNMENT. Multiple partners may quote one request, but only one partner may hold a live
 * assignment. Guarantees, layered:
 *  1. SELECT ... FOR UPDATE on the request row serialises competing assigners;
 *  2. an explicit live-assignment check returns REQUEST_ALREADY_ASSIGNED;
 *  3. the partial unique index ux_one_live_assignment is the final backstop: a concurrent insert fails
 *     with a unique violation, which is translated to REQUEST_ALREADY_ASSIGNED.
 * After assignment every other invitation/quotation is closed. Reassignment creates a NEW assignment id
 * and preserves the previous one in history.
 */
@Service
public class AssignmentService {
    public record Assignment(UUID id, String assignmentNumber, UUID requestId, UUID partnerId, UUID quotationId, String status) {}

    private record Quote(UUID id, UUID requestId, UUID partnerId, String status, BigDecimal totalAmount, BigDecimal labourAmount,
                         BigDecimal materialsAmount, BigDecimal visitFee, BigDecimal taxAmount, BigDecimal discountAmount,
                         String scopeOfWork, OffsetDateTime estimatedArrival) {}

    private static final EnumSet<RequestStatus> ASSIGNABLE = EnumSet.of(RequestStatus.CUSTOMER_APPROVAL, RequestStatus.PAYMENT_CONFIRMED,
            RequestStatus.PARTNER_DECLINED, RequestStatus.NO_SHOW);

    private final JdbcClient jdbc;
    private final BusinessIds ids;
    private final RequestRepository requests;
    private final NotificationService notifications;
    private final OpsExceptionService ops;
    private final SettingsService settings;
    private final AuditService audit;
    private final Json json;

    public AssignmentService(JdbcClient jdbc, BusinessIds ids, RequestRepository requests, NotificationService notifications,
                             OpsExceptionService ops, SettingsService settings, AuditService audit, Json json) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.requests = requests;
        this.notifications = notifications;
        this.ops = ops;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
    }

    /**
     * Creates the single live assignment for a request. Must run inside the caller's transaction or its own.
     * Throws REQUEST_ALREADY_ASSIGNED if another assignment won the race.
     */
    @Transactional
    public Assignment assignExclusive(UUID requestId, UUID quotationId, UUID actor, String actorRole, String reason) {
        RequestRepository.Row r = requests.lock(requestId);
        Integer live = jdbc.sql("SELECT count(*) FROM assignments WHERE request_id = :r AND status IN ('ACTIVE','CONFIRMED','IN_PROGRESS')")
                .param("r", requestId).query(Integer.class).single();
        if (live > 0) throw alreadyAssigned(r);
        if (!ASSIGNABLE.contains(r.statusEnum())) throw BusinessException.transition(r.status(), RequestStatus.PARTNER_ASSIGNED.name());

        Quote q = jdbc.sql("""
                SELECT id, request_id, partner_id, status, total_amount, labour_amount, materials_amount, visit_fee, tax_amount,
                       discount_amount, scope_of_work, estimated_arrival FROM quotations WHERE id = :q FOR UPDATE""")
                .param("q", quotationId).query(Quote.class).optional().orElseThrow(() -> BusinessException.notFound("Quotation"));
        if (!q.requestId().equals(requestId)) throw BusinessException.invalid("Quotation does not belong to this request");
        if (!EnumSet.of(QuoteState.SHARED, QuoteState.ACCEPTED, QuoteState.CLOSED).contains(QuoteState.valueOf(q.status()))) {
            throw new BusinessException(ErrorCode.QUOTATION_NOT_AVAILABLE, "Quotation is not available for assignment");
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("quotationId", q.id());
        snapshot.put("total", q.totalAmount());
        snapshot.put("labour", q.labourAmount());
        snapshot.put("materials", q.materialsAmount());
        snapshot.put("visitFee", q.visitFee());
        snapshot.put("tax", q.taxAmount());
        snapshot.put("discount", q.discountAmount());
        snapshot.put("scope", q.scopeOfWork());

        String number = ids.next(BusinessIds.Kind.ASSIGNMENT);
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO assignments(assignment_number, request_id, partner_id, quotation_id, status, price_snapshot, expires_at)
                    VALUES (:n, :r, :p, :q, 'ACTIVE', CAST(:snap AS jsonb), now() + make_interval(mins => :ttl)) RETURNING id""")
                    .param("n", number).param("r", requestId).param("p", q.partnerId()).param("q", quotationId)
                    .param("snap", json.write(snapshot)).param("ttl", settings.getInt("assignment.confirm_timeout_minutes", 60))
                    .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw alreadyAssigned(r);
        }
        jdbc.sql("UPDATE quotations SET status = 'ACCEPTED', decided_at = COALESCE(decided_at, now()) WHERE id = :q").param("q", quotationId).update();
        jdbc.sql("UPDATE quotations SET status = 'CLOSED' WHERE request_id = :r AND id <> :q AND status IN ('SUBMITTED','SHARED')")
                .param("r", requestId).param("q", quotationId).update();
        jdbc.sql("UPDATE partner_invitations SET status = 'CLOSED', closed_at = now() WHERE request_id = :r AND partner_id <> :p AND status IN ('INVITED','QUOTED')")
                .param("r", requestId).param("p", q.partnerId()).update();
        requests.transition(r, RequestStatus.PARTNER_ASSIGNED, actor, actorRole, reason);
        audit.record(actor, actorRole, "PARTNER_ASSIGNED", "ASSIGNMENT", id, Map.of("request", r.requestNumber(), "assignment", number));

        UUID partnerUser = partnerUser(q.partnerId());
        notifications.notifyUser(partnerUser, "Job assigned: " + r.requestNumber(), "Your quotation was accepted. Please confirm the booking.",
                "PARTNER_ASSIGNMENT", "ASSIGNMENT", id);
        notifications.notifyUser(r.customerUserId(), "Partner assigned", "A partner has been assigned to " + r.requestNumber() + ".",
                "REQUEST_UPDATE", "REQUEST", requestId);
        return new Assignment(id, number, requestId, q.partnerId(), quotationId, "ACTIVE");
    }

    private enum QuoteState { SUBMITTED, SHARED, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED, CLOSED }

    /** Partner accepts the assignment: creates the Booking. */
    @Transactional
    public Map<String, Object> confirm(UUID assignmentId, UUID partnerId, UUID partnerUserId) {
        Assignment a = lockOwned(assignmentId, partnerId);
        RequestRepository.Row r = requests.lock(a.requestId());
        if (!"ACTIVE".equals(a.status())) throw BusinessException.transition(a.status(), "CONFIRMED");
        jdbc.sql("UPDATE assignments SET status = 'CONFIRMED', updated_at = now() WHERE id = :id").param("id", assignmentId).update();
        String bookingNumber = ids.next(BusinessIds.Kind.BOOKING);
        UUID bookingId = jdbc.sql("""
                INSERT INTO bookings(booking_number, request_id, assignment_id, scheduled_at, status)
                VALUES (:n, :r, :a, (SELECT COALESCE(sr.preferred_start, q.estimated_arrival) FROM service_requests sr
                                      JOIN quotations q ON q.id = :q WHERE sr.id = :r), 'CONFIRMED') RETURNING id""")
                .param("n", bookingNumber).param("r", r.id()).param("a", assignmentId).param("q", a.quotationId())
                .query(UUID.class).single();
        requests.transition(r, RequestStatus.PARTNER_CONFIRMED, partnerUserId, "PARTNER", "Partner confirmed booking " + bookingNumber);
        notifications.notifyUser(r.customerUserId(), "Booking confirmed", "Booking " + bookingNumber + " is confirmed for " + r.requestNumber() + ".",
                "REQUEST_UPDATE", "REQUEST", r.id());
        return Map.of("booking_id", bookingId, "booking_number", bookingNumber);
    }

    /** Partner declines before confirming. */
    @Transactional
    public void decline(UUID assignmentId, UUID partnerId, UUID partnerUserId, String reason) {
        requireReason(reason);
        Assignment a = lockOwned(assignmentId, partnerId);
        RequestRepository.Row r = requests.lock(a.requestId());
        if (!"ACTIVE".equals(a.status())) throw BusinessException.transition(a.status(), "DECLINED");
        endAssignment(a, "DECLINED", "PARTNER", reason);
        requests.transition(r, RequestStatus.PARTNER_DECLINED, partnerUserId, "PARTNER", reason);
        alertAgent(r, "Partner declined " + r.requestNumber(), "Reason: " + reason + ". Please reassign.");
    }

    /** Partner cancels an accepted booking. Reason is mandatory; repeated cancellations raise an admin alert. */
    @Transactional
    public void partnerCancel(UUID assignmentId, UUID partnerId, UUID partnerUserId, String reason) {
        requireReason(reason);
        Assignment a = lockOwned(assignmentId, partnerId);
        RequestRepository.Row r = requests.lock(a.requestId());
        if (!EnumSet.of(RequestStatus.PARTNER_CONFIRMED, RequestStatus.RESCHEDULING_REQUESTED, RequestStatus.PARTNER_ASSIGNED).contains(r.statusEnum())) {
            throw BusinessException.transition(r.status(), "PARTNER_DECLINED");
        }
        endAssignment(a, "CANCELLED", "PARTNER", reason);
        jdbc.sql("UPDATE bookings SET status = 'CANCELLED' WHERE assignment_id = :a").param("a", assignmentId).update();
        requests.transition(r, RequestStatus.PARTNER_DECLINED, partnerUserId, "PARTNER", "Partner cancelled: " + reason);
        notifications.notifyUser(r.customerUserId(), "Partner cancelled", "The partner cancelled " + r.requestNumber()
                + ". Your agent is arranging a replacement.", "REQUEST_UPDATE", "REQUEST", r.id());
        alertAgent(r, "Partner cancelled " + r.requestNumber(), "Reason: " + reason + ". Please arrange another partner.");
        checkCancellationThreshold(partnerId);
    }

    /**
     * Controlled reassignment after decline/cancel/no-show: a NEW assignment id is created and the
     * previous assignment remains in history.
     */
    @Transactional
    public Assignment reassign(UUID requestId, UUID quotationId, UUID agentUserId, String reason) {
        requireReason(reason);
        RequestRepository.Row r = requests.lock(requestId);
        if (!EnumSet.of(RequestStatus.PARTNER_DECLINED, RequestStatus.NO_SHOW).contains(r.statusEnum())) {
            throw BusinessException.transition(r.status(), "PARTNER_ASSIGNED");
        }
        return assignExclusive(requestId, quotationId, agentUserId, "AGENT", "Reassigned: " + reason);
    }

    public void checkCancellationThreshold(UUID partnerId) {
        int threshold = settings.getInt("partner.cancellation.alert_threshold", 3);
        int windowDays = settings.getInt("partner.cancellation.window_days", 30);
        Integer count = jdbc.sql("""
                SELECT count(*) FROM assignments WHERE partner_id = :p AND status = 'CANCELLED' AND cancelled_by = 'PARTNER'
                  AND updated_at > now() - make_interval(days => :d)""").param("p", partnerId).param("d", windowDays)
                .query(Integer.class).single();
        if (count > threshold) {
            // Alert + review only. Partners are never auto-suspended without admin investigation.
            ops.raise("PARTNER_REPEATED_CANCELLATIONS", "HIGH",
                    "Partner cancelled " + count + " jobs in " + windowDays + " days (threshold " + threshold + ")",
                    "PARTNER", partnerId, "Review partner and contact them", "partner-cancellations:" + partnerId,
                    Map.of("count", count, "threshold", threshold, "windowDays", windowDays));
        }
    }

    public Assignment liveForRequest(UUID requestId) {
        return jdbc.sql("""
                SELECT id, assignment_number, request_id, partner_id, quotation_id, status FROM assignments
                WHERE request_id = :r AND status IN ('ACTIVE','CONFIRMED','IN_PROGRESS')""")
                .param("r", requestId).query(Assignment.class).optional().orElse(null);
    }

    /** Customer- or admin-initiated cancellation of the live assignment (if any). */
    public void cancelLive(UUID requestId, String by, String reason) {
        Assignment a = liveForRequest(requestId);
        if (a == null) return;
        endAssignment(a, "CANCELLED", by, reason);
        jdbc.sql("UPDATE bookings SET status = 'CANCELLED' WHERE assignment_id = :a").param("a", a.id()).update();
        notifications.notifyUser(partnerUser(a.partnerId()), "Booking cancelled", "The customer cancelled the booking. Reason: " + reason,
                "PARTNER_ASSIGNMENT", "ASSIGNMENT", a.id());
    }

    private void endAssignment(Assignment a, String status, String by, String reason) {
        jdbc.sql("UPDATE assignments SET status = :s, cancelled_by = :by, cancel_reason = :r, updated_at = now() WHERE id = :id")
                .param("s", status).param("by", by).param("r", reason).param("id", a.id()).update();
        // The quotation goes back to CLOSED so the agent can pick an alternative for reassignment.
        jdbc.sql("UPDATE quotations SET status = 'CLOSED' WHERE id = :q").param("q", a.quotationId()).update();
        jdbc.sql("UPDATE partner_invitations SET status = 'CLOSED', closed_at = now() WHERE request_id = :r AND partner_id = :p")
                .param("r", a.requestId()).param("p", a.partnerId()).update();
    }

    private Assignment lockOwned(UUID assignmentId, UUID partnerId) {
        Assignment a = jdbc.sql("""
                SELECT id, assignment_number, request_id, partner_id, quotation_id, status FROM assignments WHERE id = :id FOR UPDATE""")
                .param("id", assignmentId).query(Assignment.class).optional().orElseThrow(() -> BusinessException.notFound("Assignment"));
        if (!a.partnerId().equals(partnerId)) throw BusinessException.notFound("Assignment");
        return a;
    }

    private void alertAgent(RequestRepository.Row r, String title, String body) {
        if (r.agentId() == null) return;
        UUID agentUser = jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", r.agentId()).query(UUID.class).single();
        notifications.notifyUser(agentUser, title, body, "AGENT_ALERT", "REQUEST", r.id());
    }

    private UUID partnerUser(UUID partnerId) {
        return jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", partnerId).query(UUID.class).single();
    }

    private static BusinessException alreadyAssigned(RequestRepository.Row r) {
        return new BusinessException(ErrorCode.REQUEST_ALREADY_ASSIGNED, "Request " + r.requestNumber() + " is already assigned to a partner");
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 3) throw BusinessException.invalid("A reason is required");
    }
}
