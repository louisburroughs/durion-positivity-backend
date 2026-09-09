package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Resolves the single effective role/permission set a decision point evaluates a user against.
 *
 * <p>ADR-0061 amendment (2026-09-09, #1914): before this resolver existed, four call sites each
 * read a different slice of the two stores that carry a user's roles — {@code
 * CustomUserDetailsService} and {@code UserServiceImpl} unioned {@code user.getRoles()} (the
 * undated {@code user_roles} join table) with {@code role_assignments}, while {@code
 * AuthorizationServiceImpl.authorizePerson} read {@code user.getRoles()} only (so it ignored
 * effective dating) and {@code RoleManagementServiceImpl.userHasPermission} /
 * {@code getUserPermissions} read {@code role_assignments} only (so they missed {@code
 * user_roles} grants). The same user could therefore pass one decision point and fail another
 * for the same permission. Every decision point now resolves through this one type instead.
 *
 * <p>The store split itself is not removed by this phase: {@code user.getRoles()} is retired in
 * phase 2 (#1914), at which point this resolver's union collapses to the effective-assignment
 * half alone. Nothing outside {@link EffectiveGrantResolverImpl} may call {@code
 * User.getRoles()} or {@code RoleAssignmentRepository.findEffectiveAssignmentsByUser} directly —
 * enforced by {@code ArchitectureTest} in this module's test sources.
 */
public interface EffectiveGrantResolver {

    /**
     * The roles and permissions {@code user} effectively holds at {@code asOf}: the union of
     * {@code user.getRoles()} (undated {@code user_roles}) and the roles of the assignments in
     * {@code role_assignments} whose effective window covers {@code asOf}.
     *
     * @param user the user to resolve grants for
     * @param asOf the evaluation instant
     * @return the resolved roles, role names, and permission names; never null
     */
    @NonNull
    EffectiveGrants resolve(@NonNull User user, @NonNull Instant asOf);

    /**
     * Convenience overload of {@link #resolve(User, Instant)} evaluated at the current instant of
     * the implementation's injected {@link java.time.Clock}.
     *
     * @param user the user to resolve grants for
     * @return the resolved roles, role names, and permission names; never null
     */
    @NonNull
    EffectiveGrants resolve(@NonNull User user);

    /**
     * The roles and permissions a user effectively holds at one instant.
     *
     * @param roles the effective {@link Role} entities, both directly assigned and effective-dated
     * @param roleNames the names of {@code roles}
     * @param permissionNames the union of {@code Role.getPermissions()} names across {@code roles}
     */
    record EffectiveGrants(
            @NonNull Set<Role> roles,
            @NonNull Set<String> roleNames,
            @NonNull Set<String> permissionNames) {}
}
