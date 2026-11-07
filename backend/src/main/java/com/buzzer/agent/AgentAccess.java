package com.buzzer.agent;

import com.buzzer.common.BusinessException;
import com.buzzer.request.RequestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Agents may only access customers in their authorised portfolio (active primary/backup/temporary link),
 * requests escalated to them, or - for managers - the portfolios of agents they manage.
 */
@Component
public class AgentAccess {
    public record Me(UUID agentId, UUID userId, boolean manager, String displayName) {}

    private final JdbcClient jdbc;

    public AgentAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Me me(UUID userId) {
        return jdbc.sql("SELECT id AS agent_id, user_id, is_manager AS manager, display_name FROM agents WHERE user_id = :u AND active")
                .param("u", userId).query(Me.class).optional()
                .orElseThrow(() -> BusinessException.forbidden("No active agent profile for this user"));
    }

    public boolean canAccessCustomer(Me me, UUID customerUserId) {
        Integer n = jdbc.sql("""
                SELECT count(*) FROM customer_agent_links l JOIN agents a ON a.id = l.agent_id
                WHERE l.customer_user_id = :c AND l.active AND (l.agent_id = :me OR (:mgr AND a.manager_id = :me))""")
                .param("c", customerUserId).param("me", me.agentId()).param("mgr", me.manager()).query(Integer.class).single();
        return n > 0;
    }

    public void requireCustomer(Me me, UUID customerUserId) {
        if (!canAccessCustomer(me, customerUserId)) throw BusinessException.forbidden("This customer is not in your portfolio");
    }

    public void requireRequest(Me me, RequestRepository.Row r) {
        if (me.agentId().equals(r.agentId()) || me.agentId().equals(r.escalatedToAgentId())) return;
        if (me.manager() && r.agentId() != null) {
            Integer managed = jdbc.sql("SELECT count(*) FROM agents WHERE id = :a AND manager_id = :me")
                    .param("a", r.agentId()).param("me", me.agentId()).query(Integer.class).single();
            if (managed > 0) return;
        }
        if (canAccessCustomer(me, r.customerUserId())) return;
        throw BusinessException.forbidden("You are not authorised for this request");
    }
}
