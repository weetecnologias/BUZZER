package com.buzzer.execution;

import com.buzzer.assignment.AssignmentService;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BuzzerProperties;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.Hashing;
import com.buzzer.common.SettingsService;
import com.buzzer.finance.SettlementService;
import com.buzzer.notification.NotificationService;
import com.buzzer.payment.PaymentService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

/**
 * Service execution: arrival, start OTP, completion OTP, rescheduling, no-show, cancellation and reviews.
 * Service OTPs are 4-digit, short-lived, single-use, attempt-limited, hashed at rest and shown only to the
 * customer, who reads them out to the partner. If the customer's phone is unavailable, an agent can
 * verify via a controlled, audited override.
 */
@Service
public class ExecutionService {
    private static final int MAX_OTP_ATTEMPTS = 5;

    private record Booking(UUID id, String bookingNumber, UUID requestId, UUID assignmentId, String status, UUID partnerId, OffsetDateTime scheduledAt) {}

    private record Otp(UUID id, String codeHash, OffsetDateTime expiresAt, int attempts, OffsetDateTime consumedAt) {}

    private final JdbcClient jdbc;
    private final RequestRepository requests;
    private final AssignmentService assignments;
    private final SettlementService settlement;
    private final PaymentService payments;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final BuzzerProperties props;
    private final AuditService audit;

    public ExecutionService(JdbcClient jdbc, RequestRepository requests, AssignmentService assignments, SettlementService settlement,
                            PaymentService payments, NotificationService notifications, SettingsService settings,
                            BuzzerProperties props, AuditService audit) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.assignments = assignments;
        this.settlement = settlement;
        this.payments = payments;
        this.notifications = notifications;
        this.settings = settings;
        this.props = props;
        this.audit = audit;
    }

    @Transactional
    public void arrived(UUID assignmentId, UUID partnerId, UUID partnerUserId) {
        Booking b = bookingForPartner(assignmentId, partnerId);
        RequestRepository.Row r = requests.lock(b.requestId());
        requests.transition(r, RequestStatus.PARTNER_ARRIVED, partnerUserId, "PARTNER", "Partner reached the location");
        jdbc.sql("UPDATE bookings SET status = 'ARRIVED', arrived_at = now() WHERE id = :id").param("id", b.id()).update();
        notifications.notifyUser(r.customerUserId(), "Your partner has arrived", "Share the START OTP from the app to begin the service.",
                "REQUEST_UPDATE", "REQUEST", r.id());
    }

    /** Customer fetches (or regenerates) the OTP to share with the partner. */
    @Transactional
    public Map<String, Object> customerOtp(UUID requestId, UUID customerUserId, String purpose) {
        if (!"START".equals(purpose) && !"COMPLETE".equals(purpose)) throw BusinessException.invalid("Purpose must be START or COMPLETE");
        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customerUserId)) throw BusinessException.forbidden("Not your request");
        RequestStatus needed = "START".equals(purpose) ? RequestStatus.PARTNER_ARRIVED : RequestStatus.COMPLETION_PENDING;
        if (r.statusEnum() != needed && !("START".equals(purpose) && r.statusEnum() == RequestStatus.PARTNER_CONFIRMED)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "OTP is not available at this stage");
        }
        UUID bookingId = jdbc.sql("SELECT b.id FROM bookings b JOIN assignments a ON a.id = b.assignment_id WHERE b.request_id = :r AND a.status IN ('CONFIRMED','IN_PROGRESS')")
                .param("r", requestId).query(UUID.class).optional().orElseThrow(() -> BusinessException.notFound("Booking"));
        jdbc.sql("UPDATE service_otps SET consumed_at = now() WHERE booking_id = :b AND purpose = :p AND consumed_at IS NULL")
                .param("b", bookingId).param("p", purpose).update();
        String code = Hashing.numericCode(4);
        int ttl = settings.getInt("service_otp.ttl_minutes", 15);
        jdbc.sql("""
                INSERT INTO service_otps(booking_id, purpose, code_hash, expires_at) VALUES (:b, :p, :h, now() + make_interval(mins => :ttl))""")
                .param("b", bookingId).param("p", purpose).param("h", Hashing.otpHash(props.otp().pepper(), bookingId + ":" + purpose, code))
                .param("ttl", ttl).update();
        return Map.of("purpose", purpose, "otp", code, "expires_in_minutes", ttl);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void start(UUID assignmentId, UUID partnerId, UUID partnerUserId, String otp) {
        Booking b = bookingForPartner(assignmentId, partnerId);
        RequestRepository.Row r = requests.lock(b.requestId());
        if (r.statusEnum() != RequestStatus.PARTNER_ARRIVED) throw BusinessException.transition(r.status(), "SERVICE_STARTED");
        verifyOtp(b.id(), "START", otp, partnerUserId);
        doStart(b, r, partnerUserId, "CUSTOMER_OTP");
    }

    @Transactional
    public void requestCompletion(UUID assignmentId, UUID partnerId, UUID partnerUserId) {
        Booking b = bookingForPartner(assignmentId, partnerId);
        RequestRepository.Row r = requests.lock(b.requestId());
        requests.transition(r, RequestStatus.COMPLETION_PENDING, partnerUserId, "PARTNER", "Partner marked work complete");
        jdbc.sql("UPDATE bookings SET status = 'COMPLETION_PENDING' WHERE id = :id").param("id", b.id()).update();
        notifications.notifyUser(r.customerUserId(), "Work completed?", "Check the work and share the COMPLETION OTP if you are satisfied.",
                "REQUEST_UPDATE", "REQUEST", r.id());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void complete(UUID assignmentId, UUID partnerId, UUID partnerUserId, String otp) {
        Booking b = bookingForPartner(assignmentId, partnerId);
        RequestRepository.Row r = requests.lock(b.requestId());
        if (r.statusEnum() != RequestStatus.COMPLETION_PENDING) throw BusinessException.transition(r.status(), "COMPLETED");
        verifyOtp(b.id(), "COMPLETE", otp, partnerUserId);
        doComplete(b, r, partnerUserId, "CUSTOMER_OTP");
    }

    /** Controlled exception when the customer's phone is unavailable: agent verifies with the customer by call. */
    @Transactional
    public void agentOverride(UUID requestId, UUID agentUserId, String purpose, String reason) {
        if (reason == null || reason.trim().length() < 10) throw BusinessException.invalid("Explain how the customer confirmed (min 10 characters)");
        RequestRepository.Row r = requests.lock(requestId);
        Booking b = jdbc.sql("""
                SELECT b.id, b.booking_number, b.request_id, b.assignment_id, b.status, a.partner_id, b.scheduled_at
                FROM bookings b JOIN assignments a ON a.id = b.assignment_id WHERE b.request_id = :r AND a.status IN ('CONFIRMED','IN_PROGRESS')""")
                .param("r", requestId).query(Booking.class).optional().orElseThrow(() -> BusinessException.notFound("Booking"));
        audit.record(agentUserId, "AGENT", "SERVICE_OTP_OVERRIDE", "BOOKING", b.id(), Map.of("purpose", purpose, "reason", reason));
        if ("START".equals(purpose)) {
            if (r.statusEnum() != RequestStatus.PARTNER_ARRIVED) throw BusinessException.transition(r.status(), "SERVICE_STARTED");
            doStart(b, r, agentUserId, "AGENT_OVERRIDE");
        } else if ("COMPLETE".equals(purpose)) {
            if (r.statusEnum() != RequestStatus.COMPLETION_PENDING) throw BusinessException.transition(r.status(), "COMPLETED");
            doComplete(b, r, agentUserId, "AGENT_OVERRIDE");
        } else {
            throw BusinessException.invalid("Purpose must be START or COMPLETE");
        }
    }

    private void doStart(Booking b, RequestRepository.Row r, UUID verifier, String method) {
        jdbc.sql("UPDATE bookings SET status = 'STARTED', started_at = now(), start_verified_by = :v, start_verification = :m WHERE id = :id")
                .param("v", verifier).param("m", method).param("id", b.id()).update();
        jdbc.sql("UPDATE assignments SET status = 'IN_PROGRESS', updated_at = now() WHERE id = :a").param("a", b.assignmentId()).update();
        requests.transition(r, RequestStatus.SERVICE_STARTED, verifier, "AGENT_OVERRIDE".equals(method) ? "AGENT" : "PARTNER",
                "Service started (verified by " + method + ")");
        notifications.notifyUser(r.customerUserId(), "Service started", "Your service for " + r.requestNumber() + " has started.",
                "REQUEST_UPDATE", "REQUEST", r.id());
    }

    private void doComplete(Booking b, RequestRepository.Row r, UUID verifier, String method) {
        jdbc.sql("""
                UPDATE bookings SET status = 'COMPLETED', completed_at = now(), completion_verified_by = :v, completion_verification = :m WHERE id = :id""")
                .param("v", verifier).param("m", method).param("id", b.id()).update();
        jdbc.sql("UPDATE assignments SET status = 'COMPLETED', updated_at = now() WHERE id = :a").param("a", b.assignmentId()).update();
        requests.transition(r, RequestStatus.COMPLETED, verifier, "AGENT_OVERRIDE".equals(method) ? "AGENT" : "PARTNER",
                "Completion verified by " + method);
        notifications.notifyUser(r.customerUserId(), "Service completed", "Thank you! Please rate your experience for " + r.requestNumber() + ".",
                "REQUEST_REVIEW", "REQUEST", r.id());
        settlement.onCompleted(requests.lock(r.id()), verifier);
    }

    private void verifyOtp(UUID bookingId, String purpose, String code, UUID verifier) {
        Otp o = jdbc.sql("""
                SELECT id, code_hash, expires_at, attempts, consumed_at FROM service_otps WHERE booking_id = :b AND purpose = :p
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE""").param("b", bookingId).param("p", purpose).query(Otp.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.OTP_INVALID, "Ask the customer to open the app and share the OTP"));
        if (o.consumedAt() != null) throw new BusinessException(ErrorCode.OTP_INVALID, "OTP already used or replaced. Ask the customer for the latest OTP.");
        if (o.expiresAt().isBefore(OffsetDateTime.now())) throw new BusinessException(ErrorCode.OTP_EXPIRED, "OTP expired. Ask the customer to generate a new one.");
        if (o.attempts() >= MAX_OTP_ATTEMPTS) throw new BusinessException(ErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many wrong attempts. Ask the customer for a new OTP.");
        String expected = Hashing.otpHash(props.otp().pepper(), bookingId + ":" + purpose, code == null ? "" : code.trim());
        if (!Hashing.constantTimeEquals(expected, o.codeHash())) {
            jdbc.sql("UPDATE service_otps SET attempts = attempts + 1 WHERE id = :id").param("id", o.id()).update();
            throw new BusinessException(ErrorCode.OTP_INVALID, "Incorrect OTP");
        }
        jdbc.sql("UPDATE service_otps SET consumed_at = now(), consumed_by = :v WHERE id = :id").param("v", verifier).param("id", o.id()).update();
    }

    // ----- Rescheduling ---------------------------------------------------------------------------

    @Transactional
    public UUID proposeReschedule(UUID requestId, UUID userId, String role, OffsetDateTime proposedTime, String reason) {
        if (proposedTime == null || proposedTime.isBefore(OffsetDateTime.now())) throw BusinessException.invalid("Choose a future time");
        RequestRepository.Row r = requests.lock(requestId);
        Booking b = activeBooking(requestId);
        requireParty(r, b, userId, role);
        requests.transition(r, RequestStatus.RESCHEDULING_REQUESTED, userId, role, "Reschedule proposed: " + (reason == null ? "" : reason));
        UUID id = jdbc.sql("""
                INSERT INTO reschedule_proposals(booking_id, proposed_by, proposed_role, original_time, proposed_time, reason)
                VALUES (:b, :u, :role, :orig, :t, :reason) RETURNING id""")
                .param("b", b.id()).param("u", userId).param("role", role).param("orig", b.scheduledAt()).param("t", proposedTime)
                .param("reason", reason).query(UUID.class).single();
        UUID other = "CUSTOMER".equals(role) ? partnerUser(b.partnerId()) : r.customerUserId();
        notifications.notifyUser(other, "Reschedule requested", "A new time was proposed for " + r.requestNumber() + ". Please accept or reject.",
                "RESCHEDULE", "REQUEST", requestId);
        return id;
    }

    @Transactional
    public void respondReschedule(UUID requestId, UUID proposalId, UUID userId, String role, boolean accept) {
        RequestRepository.Row r = requests.lock(requestId);
        Booking b = activeBooking(requestId);
        requireParty(r, b, userId, role);
        record Proposal(UUID id, String proposedRole, OffsetDateTime proposedTime, String status) {}
        Proposal p = jdbc.sql("SELECT id, proposed_role, proposed_time, status FROM reschedule_proposals WHERE id = :id AND booking_id = :b FOR UPDATE")
                .param("id", proposalId).param("b", b.id()).query(Proposal.class).optional().orElseThrow(() -> BusinessException.notFound("Proposal"));
        if (!"PROPOSED".equals(p.status())) throw BusinessException.transition(p.status(), accept ? "ACCEPTED" : "REJECTED");
        if (p.proposedRole().equals(role)) throw BusinessException.forbidden("The other party must respond to this proposal");
        jdbc.sql("UPDATE reschedule_proposals SET status = :s, responded_by = :u, responded_at = now() WHERE id = :id")
                .param("s", accept ? "ACCEPTED" : "REJECTED").param("u", userId).param("id", proposalId).update();
        if (accept) {
            // Original and revised times both remain in reschedule_proposals history.
            jdbc.sql("UPDATE bookings SET scheduled_at = :t WHERE id = :b").param("t", p.proposedTime()).param("b", b.id()).update();
        }
        requests.transition(r, RequestStatus.PARTNER_CONFIRMED, userId, role, accept ? "Reschedule accepted" : "Reschedule rejected; original time stands");
    }

    // ----- Customer cancellation ------------------------------------------------------------------

    /** Records reason/time, cancels any live assignment and creates a PENDING refund for review (never a blanket auto-refund). */
    @Transactional
    public Map<String, Object> customerCancel(UUID requestId, UUID customerUserId, String reason) {
        if (reason == null || reason.trim().length() < 3) throw BusinessException.invalid("Please tell us why you are cancelling");
        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customerUserId)) throw BusinessException.forbidden("Not your request");
        if (!r.statusEnum().customerCancellable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "This request can no longer be cancelled from the app. Please contact your agent.");
        }
        assignments.cancelLive(requestId, "CUSTOMER", reason);
        jdbc.sql("UPDATE service_requests SET cancel_reason = :r WHERE id = :id").param("r", reason).param("id", requestId).update();
        requests.transition(r, RequestStatus.CANCELLED, customerUserId, "CUSTOMER", reason);
        record Paid(UUID id, BigDecimal amount) {}
        Paid paid = jdbc.sql("SELECT id, amount FROM payments WHERE request_id = :r AND status = 'SUCCEEDED' ORDER BY created_at DESC LIMIT 1")
                .param("r", requestId).query(Paid.class).optional().orElse(null);
        UUID refundId = null;
        if (paid != null) {
            refundId = payments.requestRefund(paid.id(), paid.amount(), "Customer cancellation: " + reason, customerUserId);
            requests.transition(requests.lock(requestId), RequestStatus.REFUND_PENDING, customerUserId, "CUSTOMER",
                    "Refund eligibility under review per cancellation policy");
        }
        if (r.agentId() != null) {
            UUID agentUser = jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", r.agentId()).query(UUID.class).single();
            notifications.notifyUser(agentUser, "Customer cancelled " + r.requestNumber(), reason, "AGENT_ALERT", "REQUEST", requestId);
        }
        return Map.of("cancelled", true, "refund_review", refundId != null);
    }

    @Transactional
    public void review(UUID requestId, UUID customerUserId, int rating, String comment) {
        if (rating < 1 || rating > 5) throw BusinessException.invalid("Rating must be 1 to 5");
        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customerUserId)) throw BusinessException.forbidden("Not your request");
        if (!EnumSet.of(RequestStatus.COMPLETED, RequestStatus.SETTLEMENT_PENDING, RequestStatus.SETTLED).contains(r.statusEnum())) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "You can rate the service once it is completed");
        }
        UUID partnerId = jdbc.sql("SELECT partner_id FROM assignments WHERE request_id = :r AND status = 'COMPLETED' ORDER BY created_at DESC LIMIT 1")
                .param("r", requestId).query(UUID.class).single();
        jdbc.sql("INSERT INTO reviews(request_id, customer_user_id, partner_id, rating, comment) VALUES (:r, :c, :p, :rating, :cm)")
                .param("r", requestId).param("c", customerUserId).param("p", partnerId).param("rating", rating).param("cm", comment).update();
        jdbc.sql("""
                UPDATE partners SET rating_avg = (SELECT round(avg(rating)::numeric, 2) FROM reviews WHERE partner_id = :p),
                  rating_count = (SELECT count(*) FROM reviews WHERE partner_id = :p) WHERE id = :p""").param("p", partnerId).update();
    }

    private Booking bookingForPartner(UUID assignmentId, UUID partnerId) {
        Booking b = jdbc.sql("""
                SELECT b.id, b.booking_number, b.request_id, b.assignment_id, b.status, a.partner_id, b.scheduled_at
                FROM bookings b JOIN assignments a ON a.id = b.assignment_id WHERE a.id = :a""")
                .param("a", assignmentId).query(Booking.class).optional().orElseThrow(() -> BusinessException.notFound("Booking"));
        if (!b.partnerId().equals(partnerId)) throw BusinessException.notFound("Booking");
        return b;
    }

    private Booking activeBooking(UUID requestId) {
        return jdbc.sql("""
                SELECT b.id, b.booking_number, b.request_id, b.assignment_id, b.status, a.partner_id, b.scheduled_at
                FROM bookings b JOIN assignments a ON a.id = b.assignment_id WHERE b.request_id = :r AND a.status IN ('CONFIRMED','IN_PROGRESS')""")
                .param("r", requestId).query(Booking.class).optional().orElseThrow(() -> BusinessException.notFound("Active booking"));
    }

    private void requireParty(RequestRepository.Row r, Booking b, UUID userId, String role) {
        boolean ok = ("CUSTOMER".equals(role) && r.customerUserId().equals(userId))
                || ("PARTNER".equals(role) && partnerUser(b.partnerId()).equals(userId));
        if (!ok) throw BusinessException.forbidden("Not a party to this booking");
    }

    private UUID partnerUser(UUID partnerId) {
        return jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", partnerId).query(UUID.class).single();
    }
}
