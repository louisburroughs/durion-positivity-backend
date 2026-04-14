package com.positivity.inventory.internal.event;

import org.jspecify.annotations.NonNull;

/**
 * Actor who performed the inventory operation, derived from the security
 * context
 * per ADR-0018 (audit actor fields from security context).
 *
 * @param personId stable person/user identifier (from JWT claim or security
 *                 context)
 * @param username display name / login (from JWT claim or security context)
 */
public record AuditActorRef(
        @NonNull String personId, @NonNull String username) {}
