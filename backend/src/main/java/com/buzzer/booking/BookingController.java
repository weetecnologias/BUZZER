package com.buzzer.booking;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.BusinessException;
import com.buzzer.identity.CurrentUser;
import com.buzzer.partner.PartnerService;
import com.buzzer.request.AttachmentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Instant service booking (customer), provider calendar and completion photos (partner), calendar view (admin). */
@RestController
@RequestMapping("/api/v1")
public class BookingController {
    public record WeeklyInput(List<AvailabilityService.WindowInput> weekly) {}

    public record RebookInput(UUID partnerId) {}

    private final BookingService bookings;
    private final AvailabilityService availability;
    private final PartnerService partners;
    private final AttachmentService attachments;
    private final JdbcClient jdbc;

    public BookingController(BookingService bookings, AvailabilityService availability, PartnerService partners, AttachmentService attachments, JdbcClient jdbc) {
        this.bookings = bookings;
        this.availability = availability;
        this.partners = partners;
        this.attachments = attachments;
        this.jdbc = jdbc;
    }

    // ----- Customer -----------------------------------------------------------------------------------

    @GetMapping("/customer/services/{id}/slots")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> slots(@PathVariable UUID id, @RequestParam UUID addressId,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.of(bookings.slots(CurrentUser.id(), id, addressId, from, days));
    }

    @GetMapping("/customer/services/{id}/providers")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<List<Map<String, Object>>> providers(@PathVariable UUID id, @RequestParam UUID addressId,
                                                            @RequestParam String slot, @RequestParam(defaultValue = "1") int quantity) {
        return ApiResponse.of(bookings.providers(CurrentUser.id(), id, addressId, parseSlot(slot), quantity));
    }

    /** ISO date-time; a "+" offset that arrived unencoded (as a space) is repaired. */
    static OffsetDateTime parseSlot(String v) {
        try {
            return OffsetDateTime.parse(v.trim().replace(' ', '+'));
        } catch (RuntimeException e) {
            throw BusinessException.invalid("slot must be an ISO date-time, e.g. 2026-11-10T10:00:00+05:30");
        }
    }

    /** Book a slot with a chosen professional. Idempotent per Idempotency-Key. */
    @PostMapping("/customer/bookings")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> book(@RequestBody BookingService.BookInput in, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.of(bookings.book(CurrentUser.id(), in, key));
    }

    @GetMapping("/customer/requests/{id}/alternatives")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<List<Map<String, Object>>> alternatives(@PathVariable UUID id) {
        return ApiResponse.of(bookings.alternatives(id, CurrentUser.id()));
    }

    @PostMapping("/customer/requests/{id}/rebook")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> rebook(@PathVariable UUID id, @RequestBody RebookInput in) {
        return ApiResponse.of(bookings.rebook(id, in == null ? null : in.partnerId(), CurrentUser.id()));
    }

    // ----- Partner: calendar ------------------------------------------------------------------------------

    @GetMapping("/partner/schedule")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> schedule() {
        return ApiResponse.of(availability.view(partners.partnerIdFor(CurrentUser.id())));
    }

    @PutMapping("/partner/schedule")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> saveSchedule(@RequestBody WeeklyInput in) {
        return ApiResponse.of(availability.saveWeekly(partners.partnerIdFor(CurrentUser.id()), in == null ? null : in.weekly(), CurrentUser.id()));
    }

    @PostMapping("/partner/schedule/time-off")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> addTimeOff(@RequestBody AvailabilityService.TimeOffInput in) {
        return ApiResponse.of(availability.addTimeOff(partners.partnerIdFor(CurrentUser.id()), in, CurrentUser.id()));
    }

    @DeleteMapping("/partner/schedule/time-off/{id}")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> removeTimeOff(@PathVariable UUID id) {
        return ApiResponse.of(availability.removeTimeOff(partners.partnerIdFor(CurrentUser.id()), id, CurrentUser.id()));
    }

    // ----- Partner: completion evidence -----------------------------------------------------------------------

    /** Before / after photos for an accepted job. Visible to the customer, their agent and admins (never to other partners). */
    @PostMapping(value = "/partner/assignments/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> uploadEvidence(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
                                                           @RequestParam(defaultValue = "AFTER") String purpose, @RequestParam(required = false) String note) {
        UUID requestId = ownJob(id, true);
        return ApiResponse.of(attachments.upload(requestId, CurrentUser.id(), "PARTNER", file, "PRIVATE", note, purpose));
    }

    @GetMapping("/partner/assignments/{id}/evidence")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<List<Map<String, Object>>> evidence(@PathVariable UUID id) {
        return ApiResponse.of(attachments.listBy(ownJob(id, false), CurrentUser.id()));
    }

    private UUID ownJob(UUID assignmentId, boolean mustBeActive) {
        UUID partnerId = partners.partnerIdFor(CurrentUser.id());
        record J(UUID requestId, UUID partnerId, String status) {}
        J j = jdbc.sql("SELECT request_id, partner_id, status FROM assignments WHERE id = :id").param("id", assignmentId).query(J.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Job"));
        if (!j.partnerId().equals(partnerId)) throw BusinessException.notFound("Job");
        if (mustBeActive && !List.of("CONFIRMED", "IN_PROGRESS").contains(j.status())) {
            throw BusinessException.invalid("Photos can be added once you have accepted the job and until it is completed");
        }
        return j.requestId();
    }

    // ----- Admin ---------------------------------------------------------------------------------------------

    @GetMapping("/admin/partners/{id}/schedule")
    public ApiResponse<Map<String, Object>> partnerSchedule(@PathVariable UUID id) {
        return ApiResponse.of(availability.view(id));
    }
}
