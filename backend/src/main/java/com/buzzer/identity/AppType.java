package com.buzzer.identity;

import java.util.EnumSet;
import java.util.Set;

/** The four independently deployed client applications and which roles may sign in to each. */
public enum AppType {
    CUSTOMER(EnumSet.noneOf(Role.class), true),
    PARTNER(EnumSet.noneOf(Role.class), true),
    AGENT(EnumSet.of(Role.AGENT, Role.AGENT_MANAGER), false),
    ADMIN(EnumSet.of(Role.ADMIN, Role.SUPER_ADMIN, Role.SUPPORT, Role.FINANCE, Role.AGENT_MANAGER), false);

    private final Set<Role> requiredAnyOf;
    private final boolean selfRegistration;

    AppType(Set<Role> requiredAnyOf, boolean selfRegistration) {
        this.requiredAnyOf = requiredAnyOf;
        this.selfRegistration = selfRegistration;
    }

    public boolean allowsSelfRegistration() {
        return selfRegistration;
    }

    public boolean permits(Set<Role> roles) {
        return requiredAnyOf.isEmpty() || roles.stream().anyMatch(requiredAnyOf::contains);
    }
}
