package com.positivity.inventory.internal.event;

import org.jspecify.annotations.NonNull;

/**
 * Actor who performed the inventory operation, derived from the security
 * context
 * per ADR-0018 (audit actor fields from security context).
 *
 * @param userId   stable person/user identifier (from {@code X-User-Id} header)
 * @param username display name / login (from {@code X-User} header)
 */
public record AuditActorRef(
        @NonNull String userId,
        @NonNull String username) {
}
