package com.buzzer.agent;

import com.buzzer.assignment.AssignmentService;
import com.buzzer.common.ApiResponse;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.PhoneNumbers;
import com.buzzer.customer.CustomerService;
import com.buzzer.execution.ExecutionService;
import com.buzzer.identity.CurrentUser;
import com.buzzer.identity.Role;
import com.buzzer.identity.UserRepository;
import com.buzzer.notification.NotificationService;
import com.buzzer.ops.EscalationService;
import com.buzzer.partner.PartnerEligibility;
import com.buzzer.payment.PaymentService;
import com.buzzer.quotation.QuotationService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
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

/** Agent App API. Agents only see their authorised portfolio and requests. */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {
    public record RegisterCustomerInput(@NotBlank String phone, @NotBlank @Size(max = 160) String fullName, @Size(max = 20) String preferredLanguage,
                                        @NotBlank String consentMethod) {}

    public record CreateRequestInput(@NotNull UUID customerUserId, UUID categoryId, UUID subcategoryId, UUID serviceItemId, UUID addressId,
                                     Map<String, Object> answers, @Size(max = 4000) String description, OffsetDateTime preferredStart,
                                     OffsetDateTime preferredEnd, String priority, @NotBlank String consentMethod) {}

    public record InviteInput(@NotEmpty List<UUID> partnerIds) {}

    public record ShareInput(@NotEmpty List<UUID> quotationIds, @Size(max = 1000) String note) {}

    public record MessageInput(@NotBlank @Size(max = 2000) String body) {}

    public record AvailabilityInput(@NotBlank String availability) {}

    public record ReassignInput(@NotNull UUID quotationId, @NotBlank String reason) {}

    public record OverrideInput(@NotBlank String purpose, @NotBlank String reason) {}

    public record CallLogInput(@NotBlank String outcome, @Size(max = 1000) String note) {}

    private final AgentAccess access;
    private final RequestService requestService;
    private final RequestRepository requests;
    private final QuotationService quotations;
    private final PartnerEligibility eligibility;
    private final AssignmentService assignments;
    private final ExecutionService execution;
    private final PaymentService payments;
    private final CustomerService customers;
    private final AgentAllocationService allocation;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final EscalationService escalations;
    private final com.buzzer.quotation.InstantQuoteService instantQuotes;
    private final com.buzzer.ops.TransferService transfers;

    public AgentController(AgentAccess access, RequestService requestService, RequestRepository requests, QuotationService quotations,
                           PartnerEligibility eligibility, AssignmentService assignments, ExecutionService execution, PaymentService payments,
                           CustomerService customers, AgentAllocationService allocation, UserRepository users,
                           NotificationService notifications, AuditService audit, JdbcClient jdbc, EscalationService escalations,
                           com.buzzer.quotation.InstantQuoteService instantQuotes, com.buzzer.ops.TransferService transfers) {
        this.escalations = escalations;
        this.instantQuotes = instantQuotes;
        this.transfers = transfers;
        this.access = access;
        this.requestService = requestService;
        this.requests = requests;
        this.quotations = quotations;
        this.eligibility = eligibility;
        this.assignments = assignments;
        this.execution = execution;
        this.payments = payments;
        this.customers = customers;
        this.allocation = allocation;
        this.users = users;
        this.notifications = notifications;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        AgentAccess.Me me = access.me(CurrentUser.id());
        return ApiResponse.of(jdbc.sql("""
                SELECT a.id, a.display_name, a.photo_url, a.availability, a.languages, a.is_manager, a.shift_start, a.shift_end,
                       c.name AS city_name, a.max_customers FROM agents a JOIN cities c ON c.id = a.city_id WHERE a.id = :a""")
                .param("a", me.agentId()).query().singleRow());
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        AgentAccess.Me me = access.me(CurrentUser.id());
        Map<String, Object> counts = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'REQUEST_CREATED') AS new_requests,
                       count(*) FILTER (WHERE status IN ('AGENT_REVIEW','PARTNER_QUOTATION','CUSTOMER_APPROVAL','PAYMENT_PENDING')) AS in_progress,
                       count(*) FILTER (WHERE status IN ('PARTNER_ASSIGNED','PARTNER_CONFIRMED','PARTNER_ARRIVED','SERVICE_STARTED','COMPLETION_PENDING')) AS active_jobs,
                       count(*) FILTER (WHERE status IN ('PARTNER_DECLINED','NO_SHOW','DISPUTED')) AS needs_attention,
                       count(*) FILTER (WHERE first_agent_response_at IS NULL AND sla_due_at < now() AND status = 'REQUEST_CREATED') AS sla_breached,
                       count(*) FILTER (WHERE status IN ('COMPLETED','SETTLEMENT_PENDING','SETTLED') AND updated_at > date_trunc('day', now())) AS completed_today
                FROM service_requests WHERE agent_id = :a OR escalated_to_agent_id = :a""").param("a", me.agentId()).query().singleRow();
        Map<String, Object> perf = jdbc.sql("""
                SELECT count(*) AS total_requests,
                       round(avg(EXTRACT(EPOCH FROM (first_agent_response_at - created_at)) / 60) FILTER (WHERE first_agent_response_at IS NOT NULL)::numeric, 1) AS avg_first_response_minutes,
                       round(avg(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600) FILTER (WHERE resolved_at IS NOT NULL)::numeric, 1) AS avg_resolution_hours,
                       (SELECT count(*) FROM customer_agent_links WHERE agent_id = :a AND active) AS portfolio_size
                FROM service_requests WHERE agent_id = :a AND created_at > now() - interval '30 days'""").param("a", me.agentId()).query().singleRow();
        return ApiResponse.of(Map.of("counts", counts, "performance_30d", perf, "agent", Map.of("id", me.agentId(), "name", me.displayName(), "manager", me.manager())));
    }

    @PutMapping("/availability")
    @Transactional
    public ApiResponse<Map<String, Object>> availability(@Valid @RequestBody AvailabilityInput in) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        if (!List.of("AVAILABLE", "BUSY", "OFFLINE", "ON_LEAVE").contains(in.availability())) throw BusinessException.invalid("Invalid availability");
        jdbc.sql("UPDATE agents SET availability = :s WHERE id = :a").param("s", in.availability()).param("a", me.agentId()).update();
        audit.record(CurrentUser.id(), "AGENT", "AGENT_AVAILABILITY_" + in.availability(), "AGENT", me.agentId(), Map.of());
        int escalated = List.of("OFFLINE", "ON_LEAVE").contains(in.availability()) ? escalations.escalateForUnavailableAgent(me.agentId()) : 0;
        return ApiResponse.of(Map.of("availability", in.availability(), "escalated_requests", escalated));
    }

    @GetMapping("/customers")
    public ApiResponse<List<Map<String, Object>>> portfolio(@RequestParam(required = false) String q) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        return ApiResponse.of(jdbc.sql("""
                SELECT DISTINCT ON (u.id) u.id, u.full_name, u.phone, l.link_role, l.created_at AS linked_at, a.display_name AS agent_name,
                       (SELECT count(*) FROM service_requests r WHERE r.customer_user_id = u.id
                          AND r.status NOT IN ('SETTLED','CANCELLED','REJECTED','EXPIRED','REFUNDED')) AS open_requests
                FROM customer_agent_links l JOIN users u ON u.id = l.customer_user_id JOIN agents a ON a.id = l.agent_id
                WHERE l.active AND (l.agent_id = :a OR (:mgr AND a.manager_id = :a))
                  AND (CAST(:q AS text) IS NULL OR u.full_name ILIKE '%' || CAST(:q AS text) || '%' OR u.phone LIKE '%' || CAST(:q AS text) || '%')
                ORDER BY u.id, l.link_role LIMIT 500""").param("a", me.agentId()).param("mgr", me.manager()).param("q", q).query().listOfRows());
    }

    @GetMapping("/customers/{customerId}")
    public ApiResponse<Map<String, Object>> customer(@PathVariable UUID customerId) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        access.requireCustomer(me, customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", customers.profile(customerId));
        out.put("addresses", customers.addresses(customerId));
        out.put("requests", requestService.customerRequests(customerId, false));
        return ApiResponse.of(out);
    }

    /** Agent registers a customer (e.g. over the phone). Consent method is recorded. */
    @PostMapping("/customers")
    @Transactional
    public ApiResponse<Map<String, Object>> registerCustomer(@Valid @RequestBody RegisterCustomerInput in) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        String phone = PhoneNumbers.normalise(in.phone());
        var user = users.findByPhone(phone).orElseGet(() -> users.create(phone, in.fullName()));
        if (users.roles(user.id()).contains(Role.CUSTOMER)) {
            access.requireCustomer(me, user.id());
            return ApiResponse.of(Map.of("customer_user_id", user.id(), "existing", true));
        }
        users.grantRole(user.id(), Role.CUSTOMER, CurrentUser.id());
        users.updateProfile(user.id(), in.fullName(), null, in.preferredLanguage());
        customers.ensureProfile(user.id(), me.agentId());
        allocation.reassign(user.id(), me.agentId(), "PRIMARY", CurrentUser.id(), "Registered by agent");
        allocation.bestAvailable(user.id(), me.agentId()).ifPresent(b -> allocation.reassign(user.id(), b.id(), "BACKUP", CurrentUser.id(), "Auto backup"));
        audit.record(CurrentUser.id(), "AGENT", "CUSTOMER_REGISTERED_BY_AGENT", "USER", user.id(), Map.of("consentMethod", in.consentMethod()));
        notifications.enqueueSms(phone, "WELCOME", Map.of("message", "Welcome to BUZZER! " + me.displayName()
                + " is your personal agent. Download the BUZZER app to track your requests."));
        return ApiResponse.of(Map.of("customer_user_id", user.id(), "existing", false));
    }

    @PostMapping("/customers/{customerId}/addresses")
    public ApiResponse<Map<String, Object>> addCustomerAddress(@PathVariable UUID customerId, @RequestBody CustomerService.AddressInput in) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        access.requireCustomer(me, customerId);
        return ApiResponse.of(Map.of("id", customers.addAddress(customerId, in)));
    }

    @PostMapping("/requests")
    public ApiResponse<RequestService.Created> createOnBehalf(@Valid @RequestBody CreateRequestInput in,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        access.requireCustomer(me, in.customerUserId());
        if (!List.of("PHONE_CALL", "WHATSAPP", "IN_PERSON", "IN_APP_CHAT", "EMAIL").contains(in.consentMethod())) {
            throw BusinessException.invalid("Record how the customer gave consent");
        }
        return ApiResponse.of(requestService.create(new RequestService.CreateCommand(in.customerUserId(), CurrentUser.id(), "AGENT_APP",
                in.categoryId(), in.subcategoryId(), in.serviceItemId(), in.addressId(), in.answers(), in.description(), in.preferredStart(),
                in.preferredEnd(), in.priority(), key, in.consentMethod())));
    }

    @GetMapping("/requests")
    public ApiResponse<List<Map<String, Object>>> inbox(@RequestParam(required = false) String status, @RequestParam(defaultValue = "200") int limit) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        return ApiResponse.of(requestService.agentRequests(me.agentId(), me.manager(), status, limit));
    }

    @GetMapping("/requests/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable UUID id) {
        authorised(id);
        Map<String, Object> d = new LinkedHashMap<>(requestService.detail(id, "AGENT"));
        d.put("quotations", quotations.forRequest(id, false));
        d.put("invitations", quotations.invitations(id));
        d.put("payments", payments.forRequest(id));
        d.put("assignments", jdbc.sql("""
                SELECT a.id, a.assignment_number, a.status, a.cancel_reason, a.cancelled_by, a.created_at, p.display_name AS partner_name
                FROM assignments a JOIN partners p ON p.id = a.partner_id WHERE a.request_id = :r ORDER BY a.created_at""")
                .param("r", id).query().listOfRows());
        return ApiResponse.of(d);
    }

    @PostMapping("/requests/{id}/acknowledge")
    public ApiResponse<Map<String, Object>> acknowledge(@PathVariable UUID id) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        access.requireRequest(me, requests.get(id));
        requestService.acknowledge(id, CurrentUser.id(), me.agentId());
        return ApiResponse.of(Map.of("status", requests.get(id).status()));
    }

    @GetMapping("/requests/{id}/eligible-partners")
    public ApiResponse<List<Map<String, Object>>> eligiblePartners(@PathVariable UUID id) {
        return ApiResponse.of(eligibility.eligibleFor(authorised(id)));
    }

    @PostMapping("/requests/{id}/invitations")
    public ApiResponse<List<Map<String, Object>>> invite(@PathVariable UUID id, @Valid @RequestBody InviteInput in) {
        authorised(id);
        return ApiResponse.of(quotations.invite(id, in.partnerIds(), CurrentUser.id()));
    }

    /** Eligible partners priced from their admin-approved price lists ("inbuilt" quotations). */
    @GetMapping("/requests/{id}/instant-quotes")
    public ApiResponse<List<Map<String, Object>>> instantQuoteOptions(@PathVariable UUID id) {
        return ApiResponse.of(instantQuotes.options(authorised(id)));
    }

    @PostMapping("/requests/{id}/instant-quotes")
    public ApiResponse<List<Map<String, Object>>> createInstantQuotes(@PathVariable UUID id, @Valid @RequestBody InviteInput in) {
        authorised(id);
        return ApiResponse.of(instantQuotes.create(id, in.partnerIds(), CurrentUser.id()));
    }

    /** Agents and managers who can take over a request. */
    @GetMapping("/colleagues")
    public ApiResponse<List<Map<String, Object>>> colleagues() {
        access.me(CurrentUser.id());
        return ApiResponse.of(transfers.staff().stream().filter(s -> s.get("agent_id") != null).toList());
    }

    public record TransferInput(@NotNull UUID agentId, @NotBlank @Size(max = 500) String reason) {}

    @PostMapping("/requests/{id}/transfer")
    public ApiResponse<Map<String, Object>> transfer(@PathVariable UUID id, @Valid @RequestBody TransferInput in) {
        authorised(id);
        transfers.transferRequest(id, in.agentId(), in.reason(), CurrentUser.id(), CurrentUser.primaryRole());
        return ApiResponse.of(Map.of("transferred", true));
    }

    @PostMapping("/requests/{id}/share-quotations")
    public ApiResponse<Map<String, Object>> share(@PathVariable UUID id, @Valid @RequestBody ShareInput in) {
        authorised(id);
        quotations.share(id, in.quotationIds(), in.note(), CurrentUser.id());
        return ApiResponse.of(Map.of("shared", in.quotationIds().size()));
    }

    @PostMapping("/requests/{id}/messages")
    public ApiResponse<Map<String, Object>> message(@PathVariable UUID id, @Valid @RequestBody MessageInput in) {
        RequestRepository.Row r = authorised(id);
        requests.addMessage(id, CurrentUser.id(), "AGENT", in.body());
        notifications.notifyUser(r.customerUserId(), "Message from your agent", in.body(), "AGENT_MESSAGE", "REQUEST", id);
        return ApiResponse.of(Map.of("sent", true));
    }

    @PostMapping("/requests/{id}/call-log")
    public ApiResponse<Map<String, Object>> callLog(@PathVariable UUID id, @Valid @RequestBody CallLogInput in) {
        authorised(id);
        requests.addMessage(id, CurrentUser.id(), "AGENT_CALL_LOG", "Call: " + in.outcome() + (in.note() == null ? "" : " - " + in.note()));
        return ApiResponse.of(Map.of("logged", true));
    }

    @PostMapping("/requests/{id}/reassign")
    public ApiResponse<AssignmentService.Assignment> reassign(@PathVariable UUID id, @Valid @RequestBody ReassignInput in) {
        authorised(id);
        return ApiResponse.of(assignments.reassign(id, in.quotationId(), CurrentUser.id(), in.reason()));
    }

    @PostMapping("/requests/{id}/verify-override")
    public ApiResponse<Map<String, Object>> verifyOverride(@PathVariable UUID id, @Valid @RequestBody OverrideInput in) {
        authorised(id);
        execution.agentOverride(id, CurrentUser.id(), in.purpose(), in.reason());
        return ApiResponse.of(Map.of("status", requests.get(id).status()));
    }

    private RequestRepository.Row authorised(UUID id) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        RequestRepository.Row r = requests.get(id);
        access.requireRequest(me, r);
        return r;
    }
}
