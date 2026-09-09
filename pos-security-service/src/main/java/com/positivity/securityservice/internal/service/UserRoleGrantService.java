package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Grants and revokes a user's roles through {@code role_assignments}, the only store of a user's
 * roles as of ADR-0061 amendment phase 2 (2026-09-09, #1914).
 *
 * <p>Every provisioning path that used to call {@code User.setRoles} now calls one of these three
 * methods instead: {@code RoleManagementServiceImpl.assignRoleToUser} / {@code
 * revokeRoleFromUser}, {@code UserServiceImpl.createUser} / {@code assignRoles} / {@code
 * updateUser}, and {@code SelfRegistrationServiceImpl.createUser}. Centralizing the writes here
 * — rather than in each of those — is what makes {@link #grant} idempotent and {@link #reconcile}
 * consistent everywhere: a caller cannot accidentally hand-roll an overlapping open-ended
 * assignment the way a direct {@code roleAssignmentRepository.save(new RoleAssignment())} could.
 */
public interface UserRoleGrantService {

    /**
     * Grants {@code role} to {@code user}, effective now with no end date.
     *
     * <p>A no-op when {@code user} already holds an effective assignment for {@code role} — this
     * is what makes repeated calls (e.g. {@code PUT /v1/users/{userId}/roles/{roleId}}) safe to
     * retry rather than accumulating overlapping open-ended rows for the same pair.
     *
     * @param user the user to grant the role to
     * @param role the role to grant
     * @param actor the name stamped as {@code created_by} on a new assignment row
     */
    void grant(@NonNull User user, @NonNull Role role, @NonNull String actor);

    /**
     * Revokes {@code user}'s currently effective assignment(s) of {@code role}, if any, by ending
     * their effective window now (see {@link com.positivity.securityservice.internal.entity.RoleAssignment#revoke}).
     *
     * <p>A no-op when {@code user} holds no effective assignment for {@code role}.
     *
     * @param user the user to revoke the role from
     * @param role the role to revoke
     * @param actor the name stamped as {@code last_modified_by} on the revoked row(s)
     */
    void revoke(@NonNull User user, @NonNull Role role, @NonNull String actor);

    /**
     * Makes {@code user}'s effective role set exactly {@code desiredRoles}: grants every role in
     * {@code desiredRoles} the user does not already effectively hold, and revokes every role the
     * user effectively holds that is not in {@code desiredRoles}. Assignment history is kept — a
     * revoked row's window is closed, not deleted.
     *
     * @param user the user to reconcile
     * @param desiredRoles the complete desired role set; an empty set revokes every effective role
     * @param actor the name stamped as {@code created_by} / {@code last_modified_by} on any row
     *     this call grants or revokes
     */
    void reconcile(@NonNull User user, @NonNull Set<Role> desiredRoles, @NonNull String actor);
}
