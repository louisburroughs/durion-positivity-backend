package com.positivity.tenant.internal.enums;

import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Tenant lifecycle (ADR-0062 §7): {@code PENDING} on create; {@code ACTIVE} once
 * {@code pos-security-service} has emitted {@code tenant.provisioned}; {@code SUSPENDED} and back
 * to {@code ACTIVE}; {@code DECOMMISSIONED} is terminal. Login for a tenant that is not
 * {@code ACTIVE} returns the bad-credentials 401.
 */
public enum TenantStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    DECOMMISSIONED;

    /** The statuses this one may move to. */
    public @NonNull Set<TenantStatus> transitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(ACTIVE, DECOMMISSIONED);
            case ACTIVE -> EnumSet.of(SUSPENDED, DECOMMISSIONED);
            case SUSPENDED -> EnumSet.of(ACTIVE, DECOMMISSIONED);
            case DECOMMISSIONED -> EnumSet.noneOf(TenantStatus.class);
        };
    }

    public boolean canTransitionTo(@NonNull TenantStatus target) {
        return transitions().contains(target);
    }

    public boolean isTerminal() {
        return this == DECOMMISSIONED;
    }
}
