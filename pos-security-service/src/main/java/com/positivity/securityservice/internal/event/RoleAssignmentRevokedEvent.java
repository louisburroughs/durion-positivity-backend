package com.positivity.securityservice.internal.event;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Published whenever a role assignment is revoked, so the token layer can end the holder's live
 * tokens after the revoking transaction commits (ADR-0061 §4 amendment, 2026-09-09, #1914 phase
 * 3).
 *
 * <p>Published by {@code UserRoleGrantServiceImpl} (covers {@code revoke} and {@code reconcile},
 * and through them {@code RoleManagementServiceImpl.revokeRoleFromUser}, {@code
 * UserServiceImpl.assignRoles} / {@code updateUser} / {@code createUser}) and by {@code
 * RoleManagementServiceImpl.revokeRoleAssignment} (which ends a specific assignment by id and does
 * not go through {@code UserRoleGrantServiceImpl}). Consumed by {@code
 * RoleAssignmentTokenRevocationListener} — see that type for why an event rather than a direct
 * call.
 */
public class RoleAssignmentRevokedEvent extends ApplicationEvent {

    private final UUID userId;

    /**
     * @param source the publishing service (conventionally {@code this})
     * @param userId the user whose role assignment was revoked; their live tokens are ended
     */
    public RoleAssignmentRevokedEvent(Object source, UUID userId) {
        super(source);
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}
