package com.buzzer.identity;

import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Accessor for the authenticated principal (populated from the verified JWT). */
public final class CurrentUser {
    private CurrentUser() {}

    public static UUID id() {
        return UUID.fromString(jwt().getSubject());
    }

    public static Set<Role> roles() {
        List<String> claim = jwt().getClaimAsStringList("roles");
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (claim != null) claim.forEach(r -> roles.add(Role.valueOf(r)));
        return roles;
    }

    public static boolean has(Role role) {
        return roles().contains(role);
    }

    /** The most privileged role, used for audit attribution. */
    public static String primaryRole() {
        Set<Role> roles = roles();
        for (Role r : List.of(Role.SUPER_ADMIN, Role.ADMIN, Role.FINANCE, Role.SUPPORT, Role.AGENT_MANAGER, Role.AGENT, Role.PARTNER, Role.CUSTOMER)) {
            if (roles.contains(r)) return r.name();
        }
        return "USER";
    }

    private static Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        return jwt;
    }
}
