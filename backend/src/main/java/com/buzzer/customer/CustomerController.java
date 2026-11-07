package com.buzzer.customer;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.BusinessException;
import com.buzzer.execution.ExecutionService;
import com.buzzer.identity.CurrentUser;
import com.buzzer.payment.PaymentService;
import com.buzzer.quotation.QuotationService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
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

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customer App API. Every endpoint is scoped to the signed-in customer's own data. */
@RestController
@RequestMapping("/api/v1/customer")
public class CustomerController {
    public record ProfileInput(@Size(max = 160) String fullName, @Size(max = 200) String email, @Size(max = 20) String preferredLanguage,
                               UUID cityId, UUID localityId, Boolean notifyPush, Boolean notifySms, Boolean notifyWhatsapp, Boolean notifyEmail) {}

    public record CreateRequestInput(UUID categoryId, UUID subcategoryId, UUID serviceItemId, UUID addressId, Map<String, Object> answers,
                                     @Size(max = 4000) String description, OffsetDateTime preferredStart, OffsetDateTime preferredEnd,
                                     String priority) {}

    public record AcceptInput(@NotBlank String paymentMethod) {}

    public record RejectInput(String reason, boolean clarification) {}

    public record ReasonInput(String reason) {}

    public record ConfirmInput(String correction) {}

    public record MessageInput(@NotBlank @Size(max = 2000) String body) {}

    public record ReviewInput(@Min(1) @Max(5) int rating, @Size(max = 2000) String comment) {}

    public record SimulateInput(@NotBlank String outcome) {}

    public record RescheduleInput(@NotNull OffsetDateTime proposedTime, String reason) {}

    public record RescheduleResponse(boolean accept) {}

    private final CustomerService customers;
    private final RequestService requestService;
    private final RequestRepository requests;
    private final QuotationService quotations;
    private final PaymentService payments;
    private final ExecutionService execution;
    private final JdbcClient jdbc;
    private final String supportPhone;
    private final AgentSubscriptionService subscriptions;
    private final com.buzzer.quotation.DirectQuoteService directQuotes;

    public CustomerController(CustomerService customers, RequestService requestService, RequestRepository requests,
                              QuotationService quotations, PaymentService payments, ExecutionService execution, JdbcClient jdbc,
                              @Value("${buzzer.support.phone:+914000000000}") String supportPhone, AgentSubscriptionService subscriptions,
                              com.buzzer.quotation.DirectQuoteService directQuotes) {
        this.directQuotes = directQuotes;
        this.subscriptions = subscriptions;
        this.customers = customers;
        this.requestService = requestService;
        this.requests = requests;
        this.quotations = quotations;
        this.payments = payments;
        this.execution = execution;
        this.jdbc = jdbc;
        this.supportPhone = supportPhone;
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        return ApiResponse.of(customers.profile(CurrentUser.id()));
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(@Valid @RequestBody ProfileInput in) {
        customers.updateProfile(CurrentUser.id(), in.fullName(), in.email(), in.preferredLanguage(), in.cityId(), in.localityId(),
                in.notifyPush(), in.notifySms(), in.notifyWhatsapp(), in.notifyEmail());
        return ApiResponse.of(customers.profile(CurrentUser.id()));
    }

    public record SubscribeInput(@Size(max = 160) String fullName, @NotNull @Size(min = 1, max = 5) List<String> languages,
                                 String contactMethod, String contactTime, @Size(max = 25) List<UUID> interestCategoryIds,
                                 UUID localityId, @Size(max = 500) String notes) {}

    /** Welcome screen state: has the customer chosen "Subscribe" or "Explore" yet, plus the subscribe form options. */
    @GetMapping("/onboarding")
    public ApiResponse<Map<String, Object>> onboarding() {
        return ApiResponse.of(subscriptions.status(CurrentUser.id()));
    }

    /** Subscribe for a dedicated agent: saves preferences and matches the best agent (language, area, availability, workload). */
    @PostMapping("/agent/subscribe")
    public ApiResponse<Map<String, Object>> subscribe(@Valid @RequestBody SubscribeInput in) {
        return ApiResponse.of(subscriptions.subscribe(CurrentUser.id(), new AgentSubscriptionService.Preferences(in.fullName(), in.languages(),
                in.contactMethod(), in.contactTime(), in.interestCategoryIds(), in.localityId(), in.notes())));
    }

    /** "Explore the app first" - no dedicated agent; requests go to the on-duty agent. */
    @PostMapping("/onboarding/explore")
    public ApiResponse<Map<String, Object>> explore() {
        return ApiResponse.of(subscriptions.explore(CurrentUser.id()));
    }

    /** Personal agent card shown directly below the Customer App header. */
    @GetMapping("/agent")
    public ApiResponse<Map<String, Object>> agent() {
        UUID me = CurrentUser.id();
        List<Map<String, Object>> links = jdbc.sql("""
                SELECT l.link_role, a.id AS agent_id, a.display_name, a.photo_url, a.availability, a.languages, u.phone,
                       (a.availability = 'AVAILABLE' AND (now() AT TIME ZONE 'Asia/Kolkata')::time BETWEEN a.shift_start AND a.shift_end) AS online
                FROM customer_agent_links l JOIN agents a ON a.id = l.agent_id JOIN users u ON u.id = a.user_id
                WHERE l.customer_user_id = :c AND l.active ORDER BY CASE l.link_role WHEN 'PRIMARY' THEN 0 WHEN 'TEMPORARY' THEN 1 ELSE 2 END""")
                .param("c", me).query().listOfRows();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primary", links.stream().filter(l -> "PRIMARY".equals(l.get("link_role"))).findFirst().orElse(null));
        out.put("backup", links.stream().filter(l -> !"PRIMARY".equals(l.get("link_role"))).findFirst().orElse(null));
        out.put("support_phone", supportPhone);
        out.put("plan", jdbc.sql("SELECT agent_plan FROM customer_profiles WHERE user_id = :u").param("u", me).query(String.class).optional().orElse(null));
        return ApiResponse.of(out);
    }

    @GetMapping("/addresses")
    public ApiResponse<List<Map<String, Object>>> addresses() {
        return ApiResponse.of(customers.addresses(CurrentUser.id()));
    }

    @PostMapping("/addresses")
    public ApiResponse<Map<String, Object>> addAddress(@RequestBody CustomerService.AddressInput in) {
        return ApiResponse.of(Map.of("id", customers.addAddress(CurrentUser.id(), in)));
    }

    @PutMapping("/addresses/{id}")
    public ApiResponse<Map<String, Object>> updateAddress(@PathVariable UUID id, @RequestBody CustomerService.AddressInput in) {
        customers.updateAddress(CurrentUser.id(), id, in);
        return ApiResponse.of(Map.of("id", id));
    }

    @DeleteMapping("/addresses/{id}")
    public ApiResponse<Map<String, Object>> deleteAddress(@PathVariable UUID id) {
        customers.deleteAddress(CurrentUser.id(), id);
        return ApiResponse.of(Map.of("deleted", true));
    }

    @PostMapping("/requests")
    public ApiResponse<RequestService.Created> createRequest(@RequestBody CreateRequestInput in,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        UUID me = CurrentUser.id();
        return ApiResponse.of(requestService.create(new RequestService.CreateCommand(me, me, "CUSTOMER_APP", in.categoryId(),
                in.subcategoryId(), in.serviceItemId(), in.addressId(), in.answers(), in.description(), in.preferredStart(),
                in.preferredEnd(), in.priority(), key, null)));
    }

    @GetMapping("/requests")
    public ApiResponse<List<Map<String, Object>>> listRequests(@RequestParam(defaultValue = "false") boolean openOnly) {
        return ApiResponse.of(requestService.customerRequests(CurrentUser.id(), openOnly));
    }

    @GetMapping("/requests/{id}")
    public ApiResponse<Map<String, Object>> requestDetail(@PathVariable UUID id) {
        RequestRepository.Row r = owned(id);
        Map<String, Object> detail = new LinkedHashMap<>(requestService.detail(id, "CUSTOMER"));
        detail.put("quotations", quotations.forRequest(id, true));
        detail.put("payments", payments.forRequest(id));
        detail.put("invoices", payments.invoicesForRequest(id));
        detail.put("booking", jdbc.sql("""
                SELECT b.id, b.booking_number, b.status, b.scheduled_at, b.arrived_at, b.started_at, b.completed_at, p.display_name AS partner_name,
                       pu.phone AS partner_phone, a.assignment_number
                FROM bookings b JOIN assignments a ON a.id = b.assignment_id JOIN partners p ON p.id = a.partner_id JOIN users pu ON pu.id = p.user_id
                WHERE b.request_id = :r ORDER BY b.created_at DESC LIMIT 1""").param("r", r.id()).query().listOfRows().stream().findFirst().orElse(null));
        detail.put("reschedule_proposals", jdbc.sql("""
                SELECT rp.id, rp.proposed_role, rp.original_time, rp.proposed_time, rp.reason, rp.status, rp.created_at
                FROM reschedule_proposals rp JOIN bookings b ON b.id = rp.booking_id WHERE b.request_id = :r ORDER BY rp.created_at DESC""")
                .param("r", r.id()).query().listOfRows());
        detail.put("review", jdbc.sql("SELECT rating, comment, created_at FROM reviews WHERE request_id = :r").param("r", r.id())
                .query().listOfRows().stream().findFirst().orElse(null));
        return ApiResponse.of(detail);
    }

    @PostMapping("/requests/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable UUID id, @RequestBody(required = false) ConfirmInput in) {
        requestService.customerConfirm(id, CurrentUser.id(), in == null ? null : in.correction());
        return ApiResponse.of(Map.of("confirmed", true));
    }

    @PostMapping("/requests/{id}/messages")
    public ApiResponse<Map<String, Object>> message(@PathVariable UUID id, @Valid @RequestBody MessageInput in) {
        owned(id);
        requests.addMessage(id, CurrentUser.id(), "CUSTOMER", in.body());
        return ApiResponse.of(Map.of("sent", true));
    }

    @PostMapping("/requests/{id}/quotations/{quotationId}/accept")
    public ApiResponse<Map<String, Object>> accept(@PathVariable UUID id, @PathVariable UUID quotationId, @Valid @RequestBody AcceptInput in,
                                                   @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.of(payments.acceptQuotation(id, quotationId, CurrentUser.id(), in.paymentMethod(), key));
    }

    public record RevisionInput(@Size(max = 500) String note) {}

    /** Ask the partner to change their quote (price, missing items, timing). They are notified and can resubmit. */
    @PostMapping("/requests/{id}/quotations/{quotationId}/revise")
    public ApiResponse<Map<String, Object>> revise(@PathVariable UUID id, @PathVariable UUID quotationId, @Valid @RequestBody RevisionInput in) {
        directQuotes.requestRevision(id, quotationId, CurrentUser.id(), in.note());
        return ApiResponse.of(Map.of("revision_requested", true));
    }

    @PostMapping("/requests/{id}/quotations/{quotationId}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable UUID id, @PathVariable UUID quotationId, @RequestBody RejectInput in) {
        quotations.customerReject(id, quotationId, CurrentUser.id(), in.reason(), in.clarification());
        return ApiResponse.of(Map.of("ok", true));
    }

    @PostMapping("/requests/{id}/payments/retry")
    public ApiResponse<PaymentService.PaymentView> retryPayment(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.of(payments.retry(id, CurrentUser.id(), key));
    }

    @PostMapping("/requests/{id}/payments/withdraw")
    public ApiResponse<Map<String, Object>> withdraw(@PathVariable UUID id) {
        payments.withdrawAcceptance(id, CurrentUser.id());
        return ApiResponse.of(Map.of("status", "CUSTOMER_APPROVAL"));
    }

    /** TEST MODE: lets testers complete or fail a mock payment through the real signed-webhook path. */
    @PostMapping("/payments/{paymentId}/simulate")
    public ApiResponse<Map<String, Object>> simulate(@PathVariable UUID paymentId, @Valid @RequestBody SimulateInput in) {
        if (!List.of("SUCCESS", "FAILURE").contains(in.outcome())) throw BusinessException.invalid("Outcome must be SUCCESS or FAILURE");
        return ApiResponse.of(Map.of("result", payments.simulate(paymentId, CurrentUser.id(), in.outcome()), "test_mode", true));
    }

    @GetMapping("/requests/{id}/service-otp")
    public ApiResponse<Map<String, Object>> serviceOtp(@PathVariable UUID id, @RequestParam String purpose) {
        return ApiResponse.of(execution.customerOtp(id, CurrentUser.id(), purpose));
    }

    @PostMapping("/requests/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable UUID id, @RequestBody ReasonInput in) {
        return ApiResponse.of(execution.customerCancel(id, CurrentUser.id(), in.reason()));
    }

    @PostMapping("/requests/{id}/reschedule")
    public ApiResponse<Map<String, Object>> reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleInput in) {
        return ApiResponse.of(Map.of("proposal_id", execution.proposeReschedule(id, CurrentUser.id(), "CUSTOMER", in.proposedTime(), in.reason())));
    }

    @PostMapping("/requests/{id}/reschedule/{proposalId}")
    public ApiResponse<Map<String, Object>> respondReschedule(@PathVariable UUID id, @PathVariable UUID proposalId, @RequestBody RescheduleResponse in) {
        execution.respondReschedule(id, proposalId, CurrentUser.id(), "CUSTOMER", in.accept());
        return ApiResponse.of(Map.of("accepted", in.accept()));
    }

    @PostMapping("/requests/{id}/review")
    public ApiResponse<Map<String, Object>> review(@PathVariable UUID id, @Valid @RequestBody ReviewInput in) {
        execution.review(id, CurrentUser.id(), in.rating(), in.comment());
        return ApiResponse.of(Map.of("reviewed", true));
    }

    @GetMapping("/home")
    public ApiResponse<Map<String, Object>> home() {
        UUID me = CurrentUser.id();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active_requests", requestService.customerRequests(me, true).stream().limit(5).toList());
        out.put("popular_services", jdbc.sql("""
                SELECT i.id, i.name, i.description, i.image_url, i.icon, i.pricing_type, i.base_price, i.min_price, i.unit, i.original_price, i.deal_label, s.id AS subcategory_id, s.name AS subcategory_name, s.icon AS subcategory_icon, c.id AS category_id, c.name AS category_name, c.workflow_template
                FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                LEFT JOIN service_requests r ON r.service_item_id = i.id AND r.created_at > now() - interval '30 days'
                WHERE i.active AND s.active AND c.active AND NOT i.is_other AND c.workflow_template IN ('MENU_DRIVEN', 'EVENT_BRIEF')
                  AND i.pricing_type <> 'QUOTE_REQUIRED'
                GROUP BY i.id, s.id, c.id ORDER BY count(r.id) DESC, c.sort_order, i.sort_order LIMIT 8""").query().listOfRows());
        return ApiResponse.of(out);
    }

    private RequestRepository.Row owned(UUID id) {
        RequestRepository.Row r = requests.get(id);
        if (!r.customerUserId().equals(CurrentUser.id())) throw BusinessException.notFound("Request");
        return r;
    }
}
