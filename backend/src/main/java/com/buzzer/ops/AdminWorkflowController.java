package com.buzzer.ops;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.identity.CurrentUser;
import com.buzzer.partner.PartnerPriceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin: ticket transfers, exception ownership, agent wings and partner price-list approvals. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminWorkflowController {
    public record TransferInput(@NotNull UUID agentId, @NotNull @Size(min = 3, max = 500) String reason) {}

    public record AssignInput(@NotNull UUID ownerUserId, @Size(max = 500) String note, UUID transferRequestToAgentId) {}

    public record AgentUpdate(@Size(min = 2, max = 60) String wing, UUID managerId, Boolean clearManager, @Min(1) @Max(5000) Integer maxCustomers,
                              Boolean isManager) {}

    public record ReviewInput(@NotEmpty List<UUID> ids, boolean approve, @Size(max = 300) String note) {}

    private final TransferService transfers;
    private final PartnerPriceService prices;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public AdminWorkflowController(TransferService transfers, PartnerPriceService prices, JdbcClient jdbc, AuditService audit) {
        this.transfers = transfers;
        this.prices = prices;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/staff")
    public ApiResponse<List<Map<String, Object>>> staff() {
        return ApiResponse.of(transfers.staff());
    }

    @GetMapping("/wings")
    public ApiResponse<List<Map<String, Object>>> wings() {
        return ApiResponse.of(jdbc.sql("""
                SELECT a.wing, count(*) AS agents, count(*) FILTER (WHERE a.is_manager) AS managers,
                       string_agg(a.display_name, ', ') FILTER (WHERE a.is_manager) AS manager_names
                FROM agents a WHERE a.active GROUP BY a.wing ORDER BY a.wing""").query().listOfRows());
    }

    @PostMapping("/requests/{id}/transfer")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','AGENT_MANAGER','SUPPORT')")
    public ApiResponse<Map<String, Object>> transferRequest(@PathVariable UUID id, @Valid @RequestBody TransferInput in) {
        transfers.transferRequest(id, in.agentId(), in.reason(), CurrentUser.id(), CurrentUser.primaryRole());
        return ApiResponse.of(Map.of("transferred", true));
    }

    /** Assign/transfer an exception to a staff member; optionally move the related request to an agent in the same step. */
    @PostMapping("/exceptions/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','AGENT_MANAGER','SUPPORT')")
    public ApiResponse<Map<String, Object>> assign(@PathVariable UUID id, @Valid @RequestBody AssignInput in) {
        transfers.assignException(id, in.ownerUserId(), in.note(), CurrentUser.id(), CurrentUser.primaryRole());
        if (in.transferRequestToAgentId() != null) {
            UUID requestId = jdbc.sql("SELECT ref_id FROM ops_exceptions WHERE id = :id AND ref_type = 'REQUEST'").param("id", id)
                    .query(UUID.class).optional().orElseThrow(() -> BusinessException.invalid("This exception is not linked to a request"));
            transfers.transferRequest(requestId, in.transferRequestToAgentId(), in.note() == null ? "Transferred from exception" : in.note(),
                    CurrentUser.id(), CurrentUser.primaryRole());
        }
        return ApiResponse.of(Map.of("assigned", true));
    }

    @PatchMapping("/agents/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> updateAgent(@PathVariable UUID id, @Valid @RequestBody AgentUpdate in) {
        if (in.managerId() != null && in.managerId().equals(id)) throw BusinessException.invalid("An agent cannot manage themselves");
        int n = jdbc.sql("""
                UPDATE agents SET wing = COALESCE(:w, wing), max_customers = COALESCE(:m, max_customers),
                  is_manager = COALESCE(:im, is_manager),
                  manager_id = CASE WHEN :clear THEN NULL ELSE COALESCE(CAST(:mid AS uuid), manager_id) END
                WHERE id = :id""")
                .param("w", in.wing() == null ? null : in.wing().trim()).param("m", in.maxCustomers()).param("im", in.isManager())
                .param("clear", Boolean.TRUE.equals(in.clearManager())).param("mid", in.managerId()).param("id", id).update();
        if (n == 0) throw BusinessException.notFound("Agent");
        if (Boolean.TRUE.equals(in.isManager())) {
            jdbc.sql("INSERT INTO user_roles(user_id, role) SELECT user_id, 'AGENT_MANAGER' FROM agents WHERE id = :id ON CONFLICT DO NOTHING")
                    .param("id", id).update();
        }
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "AGENT_UPDATED", "AGENT", id, Map.of("wing", String.valueOf(in.wing())));
        return ApiResponse.of(Map.of("updated", true));
    }

    // ----- Partner price lists ----------------------------------------------------------------------

    @GetMapping("/price-lists")
    public ApiResponse<List<Map<String, Object>>> priceLists(@RequestParam(required = false) String status) {
        return ApiResponse.of(prices.pending(status));
    }

    @PostMapping("/price-lists/review")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> review(@Valid @RequestBody ReviewInput in) {
        return ApiResponse.of(Map.of("reviewed", prices.review(in.ids(), in.approve(), in.note(), CurrentUser.id())));
    }
}
