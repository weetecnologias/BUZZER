package com.buzzer.ops;

import com.buzzer.agent.AgentAllocationService;
import com.buzzer.common.ApiResponse;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.PhoneNumbers;
import com.buzzer.common.SettingsService;
import com.buzzer.identity.CurrentUser;
import com.buzzer.identity.Role;
import com.buzzer.identity.TokenService;
import com.buzzer.identity.UserRepository;
import com.buzzer.partner.PartnerService;
import com.buzzer.quotation.QuotationService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestService;
import com.buzzer.request.RequestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin panel: operations dashboard, users, agents, partners, requests, exceptions and settings. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOperationsController {
    public record DecisionInput(@NotBlank String decision, @Size(max = 1000) String note) {}

    public record CreateAgentInput(@NotBlank String phone, @NotBlank @Size(max = 160) String displayName, @NotNull UUID cityId,
                                   String languages, @Min(1) @Max(5000) Integer maxCustomers, Boolean isManager, UUID managerId,
                                   LocalTime shiftStart, LocalTime shiftEnd, List<UUID> localityIds, @Size(max = 60) String wing) {}

    public record ReassignInput(@NotNull UUID agentId, @NotBlank String linkRole, @NotBlank String reason) {}

    public record ExceptionUpdate(@NotBlank String status, @Size(max = 1000) String note) {}

    public record SettingInput(@NotBlank @Size(max = 500) String value) {}

    public record StaffRoleInput(@NotBlank String phone, @NotBlank String role, @Size(max = 160) String fullName) {}

    public record UserStatusInput(@NotBlank String status) {}

    private final JdbcClient jdbc;
    private final PartnerService partners;
    private final AgentAllocationService allocation;
    private final OpsExceptionService ops;
    private final SettingsService settings;
    private final RequestService requestService;
    private final QuotationService quotations;
    private final UserRepository users;
    private final TokenService tokens;
    private final AuditService audit;
    private final com.buzzer.request.AttachmentService attachmentService;

    public AdminOperationsController(JdbcClient jdbc, PartnerService partners, AgentAllocationService allocation, OpsExceptionService ops,
                                     SettingsService settings, RequestService requestService, QuotationService quotations,
                                     UserRepository users, TokenService tokens, AuditService audit,
                                     com.buzzer.request.AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
        this.jdbc = jdbc;
        this.partners = partners;
        this.allocation = allocation;
        this.ops = ops;
        this.settings = settings;
        this.requestService = requestService;
        this.quotations = quotations;
        this.users = users;
        this.tokens = tokens;
        this.audit = audit;
    }

    // ----- Dashboard ------------------------------------------------------------------------------

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", jdbc.sql("""
                SELECT (SELECT count(*) FROM user_roles WHERE role = 'CUSTOMER') AS customers,
                       (SELECT count(*) FROM partners) AS partners,
                       (SELECT count(*) FROM partners WHERE registration_status = 'APPROVED' AND kyc_status = 'VERIFIED') AS verified_partners,
                       (SELECT count(*) FROM agents WHERE active) AS agents,
                       (SELECT count(*) FROM service_requests) AS requests,
                       (SELECT COALESCE(sum(amount),0) FROM ledger_entries WHERE account = 'CUSTOMER_PAYMENT' AND direction = 'CREDIT') AS gross_collected,
                       (SELECT COALESCE(sum(amount),0) FROM ledger_entries WHERE account = 'PLATFORM_COMMISSION' AND direction = 'CREDIT') AS commission_earned""")
                .query().singleRow());
        out.put("operations", jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'REQUEST_CREATED') AS pending_requests,
                       count(*) FILTER (WHERE status = 'PARTNER_QUOTATION' AND updated_at < now() - interval '4 hours') AS delayed_quotations,
                       count(*) FILTER (WHERE status = 'PARTNER_ASSIGNED' AND updated_at < now() - interval '1 hour') AS unaccepted_assignments,
                       count(*) FILTER (WHERE status = 'REQUEST_CREATED' AND first_agent_response_at IS NULL AND sla_due_at < now()) AS sla_breaches,
                       count(*) FILTER (WHERE status = 'NO_SHOW') AS no_shows,
                       count(*) FILTER (WHERE status = 'REFUND_PENDING') AS pending_refunds,
                       count(*) FILTER (WHERE status IN ('PARTNER_DECLINED')) AS needing_reassignment
                FROM service_requests""").query().singleRow());
        out.put("finance", jdbc.sql("""
                SELECT (SELECT count(*) FROM payments WHERE status = 'FAILED' AND created_at > now() - interval '7 days') AS failed_payments_7d,
                       (SELECT count(*) FROM partner_payouts WHERE status = 'PENDING') AS pending_payouts,
                       (SELECT COALESCE(sum(amount),0) FROM partner_payouts WHERE status = 'PENDING') AS pending_payout_amount,
                       (SELECT count(*) FROM refunds WHERE status = 'PENDING') AS pending_refunds""").query().singleRow());
        out.put("partners", jdbc.sql("""
                SELECT count(*) FILTER (WHERE registration_status = 'SUBMITTED') AS pending_registrations,
                       count(*) FILTER (WHERE kyc_status = 'SUBMITTED') AS pending_kyc,
                       count(*) FILTER (WHERE kyc_expires_on IS NOT NULL AND kyc_expires_on < CURRENT_DATE + 30) AS expiring_kyc,
                       (SELECT count(*) FROM partner_capabilities WHERE status = 'REQUESTED') AS pending_capabilities,
                       count(*) FILTER (WHERE availability = 'UNAVAILABLE') AS unavailable
                FROM partners""").query().singleRow());
        out.put("exceptions", ops.summary());
        out.put("status_breakdown", jdbc.sql("SELECT status, count(*) AS count FROM service_requests GROUP BY status ORDER BY count DESC").query().listOfRows());
        out.put("requests_trend", jdbc.sql("""
                SELECT to_char(d, 'YYYY-MM-DD') AS day, (SELECT count(*) FROM service_requests r WHERE r.created_at::date = d::date) AS count
                FROM generate_series(CURRENT_DATE - 29, CURRENT_DATE, interval '1 day') d ORDER BY d""").query().listOfRows());
        out.put("agent_workload", jdbc.sql("""
                SELECT a.id, a.display_name, a.availability, a.max_customers,
                       (SELECT count(*) FROM customer_agent_links l WHERE l.agent_id = a.id AND l.active) AS portfolio,
                       (SELECT count(*) FROM service_requests r WHERE r.agent_id = a.id AND r.status NOT IN ('SETTLED','CANCELLED','REJECTED','EXPIRED','REFUNDED')) AS open_requests
                FROM agents a WHERE a.active ORDER BY open_requests DESC""").query().listOfRows());
        return ApiResponse.of(out);
    }

    // ----- Customers & staff -----------------------------------------------------------------------

    @GetMapping("/customers")
    public ApiResponse<List<Map<String, Object>>> customers(@RequestParam(required = false) String q) {
        return ApiResponse.of(jdbc.sql("""
                SELECT u.id, u.full_name, u.phone, u.status, u.created_at, ci.name AS city_name, cp.agent_plan, cp.subscribed_at,
                       array_to_string(ARRAY(SELECT jsonb_array_elements_text(COALESCE(cp.agent_preferences->'languages', '[]'::jsonb))), ', ') AS languages,
                       cp.agent_preferences->>'contact_method' AS contact_method,
                       (SELECT a.display_name FROM customer_agent_links l JOIN agents a ON a.id = l.agent_id
                          WHERE l.customer_user_id = u.id AND l.active AND l.link_role = 'PRIMARY') AS primary_agent,
                       (SELECT a.display_name FROM customer_agent_links l JOIN agents a ON a.id = l.agent_id
                          WHERE l.customer_user_id = u.id AND l.active AND l.link_role = 'BACKUP') AS backup_agent,
                       (SELECT count(*) FROM service_requests r WHERE r.customer_user_id = u.id) AS request_count
                FROM users u JOIN user_roles ur ON ur.user_id = u.id AND ur.role = 'CUSTOMER'
                LEFT JOIN customer_profiles cp ON cp.user_id = u.id LEFT JOIN cities ci ON ci.id = cp.city_id
                WHERE CAST(:q AS text) IS NULL OR u.full_name ILIKE '%' || CAST(:q AS text) || '%' OR u.phone LIKE '%' || CAST(:q AS text) || '%'
                ORDER BY u.created_at DESC LIMIT 500""").param("q", q).query().listOfRows());
    }

    @PostMapping("/customers/{customerId}/agent")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','AGENT_MANAGER')")
    public ApiResponse<Map<String, Object>> reassignAgent(@PathVariable UUID customerId, @Valid @RequestBody ReassignInput in) {
        allocation.reassign(customerId, in.agentId(), in.linkRole(), CurrentUser.id(), in.reason());
        return ApiResponse.of(Map.of("reassigned", true));
    }

    @PatchMapping("/users/{userId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<Map<String, Object>> userStatus(@PathVariable UUID userId, @Valid @RequestBody UserStatusInput in) {
        if (!List.of("ACTIVE", "SUSPENDED").contains(in.status())) throw BusinessException.invalid("Status must be ACTIVE or SUSPENDED");
        users.setStatus(userId, in.status());
        if ("SUSPENDED".equals(in.status())) tokens.revokeAll(userId);
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "USER_" + in.status(), "USER", userId, Map.of());
        return ApiResponse.of(Map.of("status", in.status()));
    }

    @PostMapping("/staff")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ApiResponse<Map<String, Object>> grantStaffRole(@Valid @RequestBody StaffRoleInput in) {
        Role role = Role.valueOf(in.role());
        if (role == Role.CUSTOMER || role == Role.PARTNER) throw BusinessException.invalid("Use the customer / partner flows for these roles");
        String phone = PhoneNumbers.normalise(in.phone());
        var user = users.findByPhone(phone).orElseGet(() -> users.create(phone, in.fullName()));
        users.grantRole(user.id(), role, CurrentUser.id());
        audit.record(CurrentUser.id(), "SUPER_ADMIN", "STAFF_ROLE_GRANTED", "USER", user.id(), Map.of("role", role.name()));
        return ApiResponse.of(Map.of("user_id", user.id(), "role", role.name()));
    }

    // ----- Agents -----------------------------------------------------------------------------------

    @GetMapping("/agents")
    public ApiResponse<List<Map<String, Object>>> agents() {
        return ApiResponse.of(jdbc.sql("""
                SELECT a.id, a.display_name, u.phone, a.availability, a.languages, a.is_manager, a.wing, a.manager_id, m.display_name AS manager_name,
                       a.max_customers, a.shift_start, a.shift_end, a.active, c.name AS city_name,
                       (SELECT count(*) FROM customer_agent_links l WHERE l.agent_id = a.id AND l.active AND l.link_role = 'PRIMARY') AS primary_customers,
                       (SELECT count(*) FROM service_requests r WHERE r.agent_id = a.id AND r.status NOT IN ('SETTLED','CANCELLED','REJECTED','EXPIRED','REFUNDED')) AS open_requests,
                       (SELECT round(avg(EXTRACT(EPOCH FROM (r.first_agent_response_at - r.created_at)) / 60)::numeric, 1) FROM service_requests r
                          WHERE r.agent_id = a.id AND r.first_agent_response_at IS NOT NULL) AS avg_first_response_minutes
                FROM agents a JOIN users u ON u.id = a.user_id JOIN cities c ON c.id = a.city_id LEFT JOIN agents m ON m.id = a.manager_id
                ORDER BY a.wing, a.is_manager DESC, a.display_name""").query().listOfRows());
    }

    @PostMapping("/agents")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Transactional
    public ApiResponse<Map<String, Object>> createAgent(@Valid @RequestBody CreateAgentInput in) {
        String phone = PhoneNumbers.normalise(in.phone());
        var user = users.findByPhone(phone).orElseGet(() -> users.create(phone, in.displayName()));
        users.grantRole(user.id(), Boolean.TRUE.equals(in.isManager()) ? Role.AGENT_MANAGER : Role.AGENT, CurrentUser.id());
        UUID id = jdbc.sql("""
                INSERT INTO agents(user_id, display_name, city_id, languages, max_customers, is_manager, manager_id, shift_start, shift_end, wing)
                VALUES (:u, :n, :c, COALESCE(:l, 'en'), COALESCE(:m, 300), COALESCE(:mgr, FALSE), :mid, COALESCE(:ss, TIME '09:00'), COALESCE(:se, TIME '21:00'),
                        COALESCE(CAST(:wing AS text), 'General'))
                RETURNING id""")
                .param("u", user.id()).param("n", in.displayName()).param("c", in.cityId()).param("l", in.languages())
                .param("m", in.maxCustomers()).param("mgr", in.isManager()).param("mid", in.managerId())
                .param("ss", in.shiftStart()).param("se", in.shiftEnd()).param("wing", in.wing() == null || in.wing().isBlank() ? null : in.wing().trim())
                .query(UUID.class).single();
        if (in.localityIds() != null) {
            for (UUID l : in.localityIds()) {
                jdbc.sql("INSERT INTO agent_localities(agent_id, locality_id) VALUES (:a, :l) ON CONFLICT DO NOTHING").param("a", id).param("l", l).update();
            }
        }
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "AGENT_CREATED", "AGENT", id, Map.of("phone", PhoneNumbers.mask(phone)));
        return ApiResponse.of(Map.of("agent_id", id, "user_id", user.id()));
    }

    // ----- Partners ---------------------------------------------------------------------------------

    @GetMapping("/partners")
    public ApiResponse<List<Map<String, Object>>> partnersList(@RequestParam(required = false) String registrationStatus,
                                                               @RequestParam(required = false) String kycStatus) {
        return ApiResponse.of(jdbc.sql("""
                SELECT p.id, p.display_name, p.business_name, p.partner_type, p.contact_phone, p.registration_status, p.kyc_status,
                       p.availability, p.rating_avg, p.rating_count, p.created_at, c.name AS city_name,
                       (SELECT count(*) FROM partner_capabilities pc WHERE pc.partner_id = p.id AND pc.status = 'APPROVED') AS approved_capabilities,
                       (SELECT count(*) FROM partner_capabilities pc WHERE pc.partner_id = p.id AND pc.status = 'REQUESTED') AS pending_capabilities,
                       (SELECT count(*) FROM assignments a WHERE a.partner_id = p.id AND a.status = 'CANCELLED' AND a.cancelled_by = 'PARTNER'
                          AND a.updated_at > now() - interval '30 days') AS cancellations_30d
                FROM partners p JOIN cities c ON c.id = p.city_id
                WHERE (CAST(:rs AS text) IS NULL OR p.registration_status = CAST(:rs AS text))
                  AND (CAST(:ks AS text) IS NULL OR p.kyc_status = CAST(:ks AS text))
                ORDER BY p.created_at DESC LIMIT 500""").param("rs", registrationStatus).param("ks", kycStatus).query().listOfRows());
    }

    @GetMapping("/partners/{id}")
    public ApiResponse<Map<String, Object>> partner(@PathVariable UUID id) {
        return ApiResponse.of(partners.profile(id));
    }

    @PostMapping("/partners/{id}/registration")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> reviewRegistration(@PathVariable UUID id, @Valid @RequestBody DecisionInput in) {
        partners.reviewRegistration(id, in.decision(), in.note(), CurrentUser.id());
        return ApiResponse.of(Map.of("registration_status", in.decision()));
    }

    @PostMapping("/partners/{id}/kyc")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> reviewKyc(@PathVariable UUID id, @Valid @RequestBody DecisionInput in) {
        partners.reviewKyc(id, in.decision(), in.note(), CurrentUser.id());
        return ApiResponse.of(Map.of("kyc_status", in.decision()));
    }

    @PostMapping("/partner-capabilities/{capabilityId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> reviewCapability(@PathVariable UUID capabilityId, @Valid @RequestBody DecisionInput in) {
        partners.reviewCapability(capabilityId, in.decision(), in.note(), CurrentUser.id());
        return ApiResponse.of(Map.of("status", in.decision()));
    }

    /** Partner coverage analytics by category / subcategory (drill-down). */
    @GetMapping("/partners/coverage")
    public ApiResponse<List<Map<String, Object>>> coverage() {
        return ApiResponse.of(jdbc.sql("""
                SELECT c.name AS category_name, s.id AS subcategory_id, s.name AS subcategory_name,
                       count(DISTINCT pc.partner_id) AS registered,
                       count(DISTINCT pc.partner_id) FILTER (WHERE pc.status = 'REQUESTED') AS pending,
                       count(DISTINCT pc.partner_id) FILTER (WHERE pc.status = 'APPROVED' AND p.kyc_status = 'VERIFIED' AND p.registration_status = 'APPROVED') AS verified,
                       count(DISTINCT pc.partner_id) FILTER (WHERE pc.status = 'APPROVED' AND p.kyc_status = 'VERIFIED' AND p.registration_status = 'APPROVED' AND p.availability = 'AVAILABLE') AS available
                FROM subcategories s JOIN categories c ON c.id = s.category_id
                LEFT JOIN partner_capabilities pc ON pc.subcategory_id = s.id LEFT JOIN partners p ON p.id = pc.partner_id
                WHERE s.active AND c.active GROUP BY c.sort_order, c.name, s.sort_order, s.id, s.name ORDER BY c.sort_order, s.sort_order""")
                .query().listOfRows());
    }

    // ----- Requests ---------------------------------------------------------------------------------

    @GetMapping("/requests")
    public ApiResponse<List<Map<String, Object>>> requests(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(defaultValue = "200") int limit) {
        if (status != null && !status.matches("[A-Z_]{3,40}")) throw com.buzzer.common.BusinessException.invalid("Unknown status");
        if (type != null) com.buzzer.request.RequestType.parse(type);
        return ApiResponse.of(jdbc.sql(RequestRepository.LIST_SELECT + """
                WHERE (CAST(:s AS text) IS NULL OR r.status = CAST(:s AS text))
                  AND (CAST(:t AS text) IS NULL OR r.request_type = CAST(:t AS text))
                  AND (CAST(:q AS text) IS NULL OR r.request_number ILIKE '%' || CAST(:q AS text) || '%' OR cu.full_name ILIKE '%' || CAST(:q AS text) || '%')
                ORDER BY r.created_at DESC LIMIT :l""").param("s", status).param("q", q).param("t", type).param("l", Math.min(limit, 1000)).query().listOfRows());
    }

    @GetMapping("/requests/{id}")
    public ApiResponse<Map<String, Object>> request(@PathVariable UUID id) {
        Map<String, Object> d = new LinkedHashMap<>(requestService.detail(id, "ADMIN"));
        d.put("quotations", quotations.forRequest(id, false));
        d.put("invitations", quotations.invitations(id));
        d.put("attachments", attachmentService.list(id, false));
        d.put("order", jdbc.sql("SELECT o.id, o.order_number, o.status, o.total_amount, o.fulfilment, o.payment_method, p.display_name AS shop_name FROM orders o JOIN partners p ON p.id = o.partner_id WHERE o.request_id = :r")
                .param("r", id).query().listOfRows().stream().findFirst().orElse(null));
        d.put("assignments", jdbc.sql("""
                SELECT a.id, a.assignment_number, a.status, a.cancel_reason, a.cancelled_by, a.created_at, p.display_name AS partner_name
                FROM assignments a JOIN partners p ON p.id = a.partner_id WHERE a.request_id = :r ORDER BY a.created_at""").param("r", id).query().listOfRows());
        d.put("payments", jdbc.sql("SELECT id, payment_number, method, amount, status, failure_reason, test_mode, created_at FROM payments WHERE request_id = :r ORDER BY created_at")
                .param("r", id).query().listOfRows());
        d.put("ledger", jdbc.sql("SELECT account, direction, amount, memo, created_at FROM ledger_entries WHERE request_id = :r ORDER BY created_at")
                .param("r", id).query().listOfRows());
        d.put("audit", jdbc.sql("SELECT action, actor_role, details::text AS details, created_at FROM audit_events WHERE entity_id = :r ORDER BY created_at")
                .param("r", id).query().listOfRows());
        return ApiResponse.of(d);
    }

    @GetMapping("/request-states")
    public ApiResponse<Map<RequestStatus, java.util.Set<RequestStatus>>> states() {
        return ApiResponse.of(RequestStatus.transitions());
    }

    // ----- Exceptions & settings --------------------------------------------------------------------

    @GetMapping("/exceptions")
    public ApiResponse<List<Map<String, Object>>> exceptions(@RequestParam(required = false) String status, @RequestParam(required = false) String type) {
        return ApiResponse.of(ops.list(status, type, 300));
    }

    @PatchMapping("/exceptions/{id}")
    public ApiResponse<Map<String, Object>> updateException(@PathVariable UUID id, @Valid @RequestBody ExceptionUpdate in) {
        ops.updateStatus(id, in.status(), CurrentUser.id(), in.note());
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "EXCEPTION_" + in.status(), "OPS_EXCEPTION", id, Map.of());
        return ApiResponse.of(Map.of("status", in.status()));
    }

    @GetMapping("/settings")
    public ApiResponse<List<Map<String, Object>>> settingsList() {
        return ApiResponse.of(settings.all());
    }

    @PutMapping("/settings/{key}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> putSetting(@PathVariable String key, @Valid @RequestBody SettingInput in) {
        settings.put(key, in.value(), CurrentUser.id());
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "SETTING_CHANGED", "SETTING", null, Map.of("key", key, "value", in.value()));
        return ApiResponse.of(Map.of("key", key, "value", in.value()));
    }

    @GetMapping("/sla-rules")
    public ApiResponse<List<Map<String, Object>>> slaRules() {
        return ApiResponse.of(jdbc.sql("SELECT id, priority, first_response_minutes, resolution_minutes, active FROM sla_rules ORDER BY first_response_minutes")
                .query().listOfRows());
    }

    public record SlaInput(@Min(5) @Max(10080) int firstResponseMinutes, @Min(15) @Max(43200) int resolutionMinutes) {}

    @PutMapping("/sla-rules/{priority}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> putSla(@PathVariable String priority, @Valid @RequestBody SlaInput in) {
        int n = jdbc.sql("UPDATE sla_rules SET first_response_minutes = :f, resolution_minutes = :r WHERE priority = :p")
                .param("f", in.firstResponseMinutes()).param("r", in.resolutionMinutes()).param("p", priority).update();
        if (n == 0) throw BusinessException.notFound("SLA rule");
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "SLA_RULE_CHANGED", "SLA_RULE", null, Map.of("priority", priority));
        return ApiResponse.of(Map.of("priority", priority));
    }
}
