package com.buzzer.partner;

import com.buzzer.assignment.AssignmentService;
import com.buzzer.common.ApiResponse;
import com.buzzer.execution.ExecutionService;
import com.buzzer.finance.LedgerService;
import com.buzzer.identity.CurrentUser;
import com.buzzer.quotation.QuotationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/partner")
public class PartnerController {
    public record CapabilityRequest(@NotNull UUID subcategoryId, UUID serviceItemId) {}

    public record KycInput(@NotBlank String docType, @NotBlank String docNumber) {}

    public record AvailabilityInput(@NotBlank String availability) {}

    public record ReasonInput(String reason) {}

    public record OtpInput(@NotBlank String otp) {}

    public record RescheduleInput(@NotNull OffsetDateTime proposedTime, String reason) {}

    public record RescheduleResponse(boolean accept) {}

    private final PartnerService partners;
    private final QuotationService quotations;
    private final AssignmentService assignments;
    private final ExecutionService execution;
    private final LedgerService ledger;
    private final JdbcClient jdbc;
    private final PartnerPriceService prices;
    private final com.buzzer.quotation.DirectQuoteService directQuotes;

    public record PriceListInput(List<PartnerPriceService.PriceInput> prices) {}

    public PartnerController(PartnerService partners, QuotationService quotations, AssignmentService assignments,
                             ExecutionService execution, LedgerService ledger, JdbcClient jdbc, PartnerPriceService prices,
                             com.buzzer.quotation.DirectQuoteService directQuotes) {
        this.directQuotes = directQuotes;
        this.prices = prices;
        this.partners = partners;
        this.quotations = quotations;
        this.assignments = assignments;
        this.execution = execution;
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @GetMapping("/terms")
    public ApiResponse<Map<String, Object>> currentTerms() {
        return ApiResponse.of(jdbc.sql("""
                SELECT id, version, title, summary, document_url, effective_from FROM terms_versions
                WHERE audience = 'PARTNER' AND active ORDER BY effective_from DESC LIMIT 1""").query().singleRow());
    }

    @PostMapping("/registration")
    public ApiResponse<Map<String, Object>> register(@RequestBody PartnerService.Registration in, HttpServletRequest http) {
        UUID id = partners.register(CurrentUser.id(), in, http.getRemoteAddr(), http.getHeader("User-Agent"));
        return ApiResponse.of(Map.of("partner_id", id, "registration_status", "SUBMITTED", "kyc_status", "PENDING",
                "next_step", "Refresh your session, then submit KYC documents"));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> me() {
        return ApiResponse.of(partners.profile(partners.partnerIdFor(CurrentUser.id())));
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> dashboard() {
        UUID p = partners.partnerIdFor(CurrentUser.id());
        Map<String, Object> counts = jdbc.sql("""
                SELECT (SELECT count(*) FROM partner_invitations WHERE partner_id = :p AND status = 'INVITED') AS new_leads,
                       (SELECT count(*) FROM partner_invitations WHERE partner_id = :p AND status = 'QUOTED') AS quotes_pending,
                       (SELECT count(*) FROM assignments WHERE partner_id = :p AND status = 'ACTIVE') AS awaiting_confirmation,
                       (SELECT count(*) FROM assignments WHERE partner_id = :p AND status IN ('CONFIRMED','IN_PROGRESS')) AS in_progress,
                       (SELECT count(*) FROM assignments WHERE partner_id = :p AND status = 'COMPLETED') AS completed,
                       (SELECT count(*) FROM orders WHERE partner_id = :p AND status IN ('PLACED','PAID')) AS new_orders,
                       (SELECT count(*) FROM orders WHERE partner_id = :p AND status IN ('VENDOR_CONFIRMED','READY_FOR_PICKUP','OUT_FOR_DELIVERY')) AS active_orders,
                       EXISTS (SELECT 1 FROM partner_roles WHERE partner_id = :p AND status = 'APPROVED' AND role IN ('RETAIL_SHOP','WHOLESALER','DISTRIBUTOR')) AS is_shop""")
                .param("p", p).query().singleRow();
        return ApiResponse.of(Map.of("counts", counts, "earnings", ledger.partnerSummary(p), "profile", partners.profile(p).get("partner")));
    }

    @PostMapping("/capabilities")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> requestCapability(@Valid @RequestBody CapabilityRequest in) {
        UUID id = partners.requestCapability(partners.partnerIdFor(CurrentUser.id()), in.subcategoryId(), in.serviceItemId());
        return ApiResponse.of(Map.of("capability_id", id, "status", "REQUESTED"));
    }

    @PostMapping("/kyc")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> kyc(@Valid @RequestBody KycInput in) {
        partners.submitKyc(partners.partnerIdFor(CurrentUser.id()), in.docType(), in.docNumber());
        return ApiResponse.of(Map.of("kyc_status", "SUBMITTED"));
    }

    @PutMapping("/availability")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> availability(@Valid @RequestBody AvailabilityInput in) {
        partners.setAvailability(partners.partnerIdFor(CurrentUser.id()), in.availability());
        return ApiResponse.of(Map.of("availability", in.availability()));
    }

    @GetMapping("/leads")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<List<Map<String, Object>>> leads(@RequestParam(required = false) String status) {
        return ApiResponse.of(partners.leads(partners.partnerIdFor(CurrentUser.id()), status));
    }

    @PostMapping("/leads/{invitationId}/quote")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> quote(@PathVariable UUID invitationId, @RequestBody QuotationService.QuoteInput in) {
        return ApiResponse.of(quotations.submit(invitationId, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), in));
    }

    @PostMapping("/leads/{invitationId}/decline")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> declineLead(@PathVariable UUID invitationId, @RequestBody ReasonInput in) {
        UUID requestId = quotations.decline(invitationId, partners.partnerIdFor(CurrentUser.id()), in.reason());
        directQuotes.afterDecline(requestId);
        return ApiResponse.of(Map.of("declined", true));
    }

    /** One lead with every requested item, the partner's suggested prices and their latest quote. */
    @GetMapping("/leads/{invitationId}")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> lead(@PathVariable UUID invitationId) {
        return ApiResponse.of(directQuotes.leadDetail(invitationId, partners.partnerIdFor(CurrentUser.id())));
    }

    /** Item-by-item quote (availability + price per item, delivery fee). Submitting again revises the quote. */
    @PostMapping("/leads/{invitationId}/item-quote")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> itemQuote(@PathVariable UUID invitationId, @RequestBody com.buzzer.quotation.DirectQuoteService.ItemQuoteInput in) {
        return ApiResponse.of(directQuotes.submitItemQuote(invitationId, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), in));
    }

    @GetMapping("/assignments")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<List<Map<String, Object>>> assignments(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return ApiResponse.of(partners.assignments(partners.partnerIdFor(CurrentUser.id()), activeOnly));
    }

    @PostMapping("/assignments/{id}/confirm")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable UUID id) {
        return ApiResponse.of(assignments.confirm(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id()));
    }

    @PostMapping("/assignments/{id}/decline")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> decline(@PathVariable UUID id, @RequestBody ReasonInput in) {
        assignments.decline(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), in.reason());
        return ApiResponse.of(Map.of("declined", true));
    }

    @PostMapping("/assignments/{id}/cancel")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable UUID id, @RequestBody ReasonInput in) {
        assignments.partnerCancel(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), in.reason());
        return ApiResponse.of(Map.of("cancelled", true));
    }

    @PostMapping("/assignments/{id}/arrived")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> arrived(@PathVariable UUID id) {
        execution.arrived(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id());
        return ApiResponse.of(Map.of("status", "PARTNER_ARRIVED"));
    }

    @PostMapping("/assignments/{id}/start")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> start(@PathVariable UUID id, @Valid @RequestBody OtpInput in) {
        execution.start(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), in.otp());
        return ApiResponse.of(Map.of("status", "SERVICE_STARTED"));
    }

    @PostMapping("/assignments/{id}/request-completion")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> requestCompletion(@PathVariable UUID id) {
        execution.requestCompletion(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id());
        return ApiResponse.of(Map.of("status", "COMPLETION_PENDING"));
    }

    @PostMapping("/assignments/{id}/complete")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> complete(@PathVariable UUID id, @Valid @RequestBody OtpInput in) {
        execution.complete(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), in.otp());
        return ApiResponse.of(Map.of("status", "COMPLETED"));
    }

    @PostMapping("/requests/{requestId}/reschedule")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> reschedule(@PathVariable UUID requestId, @Valid @RequestBody RescheduleInput in) {
        partners.partnerIdFor(CurrentUser.id());
        return ApiResponse.of(Map.of("proposal_id", execution.proposeReschedule(requestId, CurrentUser.id(), "PARTNER", in.proposedTime(), in.reason())));
    }

    @PostMapping("/requests/{requestId}/reschedule/{proposalId}")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> respondReschedule(@PathVariable UUID requestId, @PathVariable UUID proposalId, @RequestBody RescheduleResponse in) {
        execution.respondReschedule(requestId, proposalId, CurrentUser.id(), "PARTNER", in.accept());
        return ApiResponse.of(Map.of("accepted", in.accept()));
    }

    @GetMapping("/price-list")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<List<Map<String, Object>>> priceList() {
        return ApiResponse.of(prices.priceList(partners.partnerIdFor(CurrentUser.id())));
    }

    @PutMapping("/price-list")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> savePrices(@RequestBody PriceListInput in) {
        return ApiResponse.of(prices.save(partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), in.prices()));
    }

    @GetMapping("/price-list/excel")
    @PreAuthorize("hasRole('PARTNER')")
    public org.springframework.http.ResponseEntity<byte[]> priceTemplate() {
        return com.buzzer.common.ExcelSupport.download("my-price-list.xlsx", prices.template(partners.partnerIdFor(CurrentUser.id())));
    }

    @PostMapping("/price-list/excel")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> priceUpload(@org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.of(prices.importExcel(partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), file));
    }

    @GetMapping("/earnings")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> earnings() {
        UUID p = partners.partnerIdFor(CurrentUser.id());
        return ApiResponse.of(Map.of("summary", ledger.partnerSummary(p), "entries", ledger.partnerEntries(p, 100), "payouts", partners.payouts(p)));
    }
}
