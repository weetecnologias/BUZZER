package com.buzzer.agent;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.identity.CurrentUser;
import com.buzzer.ops.SlaPolicy;
import com.buzzer.request.RequestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 7: agent drafts (customer approves), activity log, workspace dashboard; customer approvals; admin SLA per vertical
 * and agent eligibility. Agents can prepare but never approve, accept or pay - those endpoints are customer-only.
 */
@RestController
@RequestMapping("/api/v1")
public class AgentWorkController {
    public record ActivityInput(String kind, String summary, UUID customerId, UUID requestId, UUID partnerId) {}

    public record SlaInput(Integer firstResponseMinutes) {}

    public record EligibilityInput(List<String> verticals, Boolean acceptsDedicated) {}

    private final AgentAccess access;
    private final DraftService drafts;
    private final AgentActivityService activity;
    private final AgentDashboardService dashboard;
    private final SlaPolicy sla;
    private final RequestRepository requests;
    private final JdbcClient jdbc;
    private final AuditService audit;
    @org.springframework.beans.factory.annotation.Autowired
    private com.buzzer.booking.BookingService bookings;

    public AgentWorkController(AgentAccess access, DraftService drafts, AgentActivityService activity, AgentDashboardService dashboard, SlaPolicy sla,
                               RequestRepository requests, JdbcClient jdbc, AuditService audit) {
        this.access = access;
        this.drafts = drafts;
        this.activity = activity;
        this.dashboard = dashboard;
        this.sla = sla;
        this.requests = requests;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ----- Agent ------------------------------------------------------------------------------------------

    @GetMapping("/agent/workspace")
    public ApiResponse<Map<String, Object>> workspace() {
        return ApiResponse.of(dashboard.workspace(access.me(CurrentUser.id()).agentId()));
    }

    /** Prepare an RFQ, a one-shop product cart or a service booking for a customer in the agent's portfolio. */
    @PostMapping("/agent/customers/{customerId}/drafts")
    public ApiResponse<Map<String, Object>> createDraft(@PathVariable UUID customerId, @RequestBody DraftService.DraftInput in) {
        return ApiResponse.of(drafts.create(access.me(CurrentUser.id()), customerId, in));
    }

    /** Free slots for a service at one of the customer's addresses (to prepare a booking draft). */
    @GetMapping("/agent/customers/{customerId}/services/{serviceId}/slots")
    public ApiResponse<Map<String, Object>> slots(@PathVariable UUID customerId, @PathVariable UUID serviceId, @RequestParam UUID addressId) {
        access.requireCustomer(access.me(CurrentUser.id()), customerId);
        return ApiResponse.of(bookings.slots(customerId, serviceId, addressId, null, 7));
    }

    @GetMapping("/agent/customers/{customerId}/services/{serviceId}/providers")
    public ApiResponse<List<Map<String, Object>>> providers(@PathVariable UUID customerId, @PathVariable UUID serviceId, @RequestParam UUID addressId,
                                                            @RequestParam String slot) {
        access.requireCustomer(access.me(CurrentUser.id()), customerId);
        return ApiResponse.of(bookings.providers(customerId, serviceId, addressId, java.time.OffsetDateTime.parse(slot.trim().replace(' ', '+')), 1));
    }

    @GetMapping("/agent/drafts")
    public ApiResponse<List<Map<String, Object>>> agentDrafts(@RequestParam(required = false) String status, @RequestParam(required = false) UUID customerId) {
        return ApiResponse.of(drafts.forAgent(access.me(CurrentUser.id()), status, customerId));
    }

    @PostMapping("/agent/drafts/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelDraft(@PathVariable UUID id) {
        return ApiResponse.of(drafts.cancel(access.me(CurrentUser.id()), id));
    }

    @GetMapping("/agent/activity")
    public ApiResponse<List<Map<String, Object>>> agentActivity(@RequestParam(required = false) UUID customerId, @RequestParam(required = false) UUID requestId,
                                                                @RequestParam(defaultValue = "100") int limit) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        if (customerId != null) access.requireCustomer(me, customerId);
        return ApiResponse.of(activity.list(me.agentId(), me.manager(), customerId, requestId, limit));
    }

    /** Log a call, a note or a vendor follow-up (optionally for a customer, request or partner). */
    @PostMapping("/agent/activity")
    public ApiResponse<Map<String, Object>> logActivity(@RequestBody ActivityInput in) {
        AgentAccess.Me me = access.me(CurrentUser.id());
        String kind = in == null || in.kind() == null ? "" : in.kind().toUpperCase();
        if (!AgentActivityService.MANUAL.contains(kind)) throw BusinessException.invalid("Kind must be CALL, NOTE or VENDOR_FOLLOW_UP");
        if (in.summary() == null || in.summary().trim().length() < 3 || in.summary().length() > 500) throw BusinessException.invalid("Write 3-500 characters");
        UUID customer = in.customerId();
        if (in.requestId() != null) {
            RequestRepository.Row r = requests.get(in.requestId());
            access.requireRequest(me, r);
            customer = r.customerUserId();
        } else if (customer != null) {
            access.requireCustomer(me, customer);
        }
        if (in.partnerId() != null && jdbc.sql("SELECT count(*) FROM partners WHERE id = :p").param("p", in.partnerId()).query(Integer.class).single() == 0) {
            throw BusinessException.notFound("Partner");
        }
        activity.log(me.agentId(), kind, in.summary().trim(), customer, in.requestId(), in.partnerId(), null);
        return ApiResponse.of(Map.of("logged", true));
    }

    // ----- Customer ----------------------------------------------------------------------------------------

    @GetMapping("/customer/drafts")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<List<Map<String, Object>>> customerDrafts(@RequestParam(required = false) String status) {
        return ApiResponse.of(drafts.forCustomer(CurrentUser.id(), status));
    }

    @GetMapping("/customer/drafts/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> customerDraft(@PathVariable UUID id) {
        return ApiResponse.of(drafts.customerView(CurrentUser.id(), id));
    }

    @PostMapping("/customer/drafts/{id}/approve")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> approve(@PathVariable UUID id, @RequestBody(required = false) DraftService.Decision in) {
        return ApiResponse.of(drafts.approve(CurrentUser.id(), id, in));
    }

    @PostMapping("/customer/drafts/{id}/reject")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> reject(@PathVariable UUID id, @RequestBody(required = false) DraftService.Decision in) {
        return ApiResponse.of(drafts.reject(CurrentUser.id(), id, in));
    }

    // ----- Admin ---------------------------------------------------------------------------------------------

    @GetMapping("/admin/sla/verticals")
    public ApiResponse<List<Map<String, Object>>> slaVerticals() {
        return ApiResponse.of(sla.verticals());
    }

    @PutMapping("/admin/sla/verticals/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<List<Map<String, Object>>> updateSla(@PathVariable String code, @RequestBody SlaInput in) {
        return ApiResponse.of(sla.update(code.toUpperCase(), in == null ? null : in.firstResponseMinutes(), CurrentUser.id()));
    }

    @GetMapping("/admin/agents/{id}/eligibility")
    public ApiResponse<Map<String, Object>> eligibility(@PathVariable UUID id) {
        return ApiResponse.of(eligibilityView(id));
    }

    /** Which verticals an agent is trained for, and whether they take dedicated customers. */
    @PutMapping("/admin/agents/{id}/eligibility")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','AGENT_MANAGER')")
    @Transactional
    public ApiResponse<Map<String, Object>> updateEligibility(@PathVariable UUID id, @RequestBody EligibilityInput in) {
        Integer exists = jdbc.sql("SELECT count(*) FROM agents WHERE id = :a").param("a", id).query(Integer.class).single();
        if (exists == 0) throw BusinessException.notFound("Agent");
        if (in == null) throw BusinessException.invalid("Nothing to update");
        if (in.verticals() != null) {
            Set<String> known = Set.copyOf(jdbc.sql("SELECT code FROM platform_verticals").query(String.class).list());
            for (String v : in.verticals()) if (!known.contains(v)) throw BusinessException.invalid("Unknown vertical " + v);
            if (in.verticals().isEmpty()) throw BusinessException.invalid("An agent needs at least one vertical");
            jdbc.sql("DELETE FROM agent_skills WHERE agent_id = :a").param("a", id).update();
            for (String v : in.verticals().stream().distinct().toList()) {
                jdbc.sql("INSERT INTO agent_skills(agent_id, vertical) VALUES (:a, :v)").param("a", id).param("v", v).update();
            }
        }
        if (in.acceptsDedicated() != null) {
            jdbc.sql("UPDATE agents SET accepts_dedicated = :d WHERE id = :a").param("d", in.acceptsDedicated()).param("a", id).update();
        }
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "AGENT_ELIGIBILITY_UPDATED", "AGENT", id,
                Map.of("verticals", String.valueOf(in.verticals()), "acceptsDedicated", String.valueOf(in.acceptsDedicated())));
        return ApiResponse.of(eligibilityView(id));
    }

    @GetMapping("/admin/agents/{id}/activity")
    public ApiResponse<List<Map<String, Object>>> adminAgentActivity(@PathVariable UUID id, @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.of(activity.list(id, false, null, null, limit));
    }

    @GetMapping("/admin/requests/{id}/agent-activity")
    public ApiResponse<List<Map<String, Object>>> requestAgentActivity(@PathVariable UUID id) {
        return ApiResponse.of(activity.forScope(null, id, 200));
    }

    private Map<String, Object> eligibilityView(UUID id) {
        return Map.of("accepts_dedicated", jdbc.sql("SELECT accepts_dedicated FROM agents WHERE id = :a").param("a", id).query(Boolean.class).single(),
                "verticals", jdbc.sql("SELECT vertical FROM agent_skills WHERE agent_id = :a ORDER BY vertical").param("a", id).query(String.class).list());
    }
}
