package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Resolves the single effective role/permission set a decision point evaluates a user against.
 *
 * <p>ADR-0061 amendment (2026-09-09, #1914): before this resolver existed, four call sites each
 * read a different slice of the two stores that used to carry a user's roles — {@code
 * CustomUserDetailsService} and {@code UserServiceImpl} unioned {@code user.getRoles()} (the
 * undated {@code user_roles} join table) with {@code role_assignments}, while {@code
 * AuthorizationServiceImpl.authorizePerson} read {@code user.getRoles()} only (so it ignored
 * effective dating) and {@code RoleManagementServiceImpl.userHasPermission} /
 * {@code getUserPermissions} read {@code role_assignments} only (so they missed {@code
 * user_roles} grants). The same user could therefore pass one decision point and fail another
 * for the same permission. Every decision point now resolves through this one type instead.
 *
 * <p>Phase 2 (#1914) retired the store split: {@code user_roles} is dropped (the retired V40
 * migration moved every row into an open-ended {@code role_assignments} row first; the flattened
 * baseline never creates the table) and {@code User.getRoles()} no longer exists, so this resolver's
 * "union" is now just the effective-dated {@code role_assignments} rows for the user. It stays a
 * named type — rather than callers reading {@code RoleAssignmentRepository} directly — because it
 * is still the one place that turns a raw query result into roles / role names / permission
 * names, and because {@code EffectiveGrants.assignments()} gives a listing caller the rows
 * themselves without a second query. Nothing outside {@link EffectiveGrantResolverImpl} may call
 * {@code RoleAssignmentRepository.findEffectiveAssignmentsByUser} directly — enforced by
 * {@code ArchitectureTest} in this module's test sources.
 */
public interface EffectiveGrantResolver {

    /**
     * The roles and permissions {@code user} effectively holds at {@code asOf}: the roles of the
     * assignments in {@code role_assignments} whose effective window covers {@code asOf}.
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
     * @param roles the effective {@link Role} entities, resolved from {@code assignments}
     * @param roleNames the names of {@code roles}
     * @param permissionNames the union of {@code Role.getPermissions()} names across {@code roles}
     * @param assignments the {@code role_assignments} rows effective at the resolved instant — the
     *     same rows {@code roles} partly derives from, kept here so an assignment-listing caller
     *     (e.g. {@code RoleManagementServiceImpl.getAssignmentEntitiesForUser}) can read the rows
     *     themselves through this one query instead of re-querying
     *     {@code findEffectiveAssignmentsByUser} directly (#1914 Part A)
     */
    record EffectiveGrants(
            @NonNull Set<Role> roles,
            @NonNull Set<String> roleNames,
            @NonNull Set<String> permissionNames,
            @NonNull List<RoleAssignment> assignments) {

        /**
         * Convenience constructor for callers that do not need the raw assignment rows (most
         * tests, and every call site predating #1914 Part A).
         */
        public EffectiveGrants(
                @NonNull Set<Role> roles, @NonNull Set<String> roleNames, @NonNull Set<String> permissionNames) {
            this(roles, roleNames, permissionNames, List.of());
        }

        /**
         * The earliest {@code effectiveEndDate} among {@link #assignments()}, if any assignment is
         * bounded — the bound a reissued access token's {@code exp} is clamped to alongside the
         * existing location-reach clamp (ADR-0061 §4 amendment, 2026-09-09, #1914 phase 3).
         *
         * <p>{@code effectiveEndDate} is a {@link LocalDateTime}; it is converted to an {@link
         * Instant} with {@code clock.getZone()}, the same conversion {@link
         * EffectiveGrantResolverImpl#resolve(User, Instant)} uses in the other direction, so a
         * fixed-zone test clock and the production {@code Clock.systemUTC()} agree with the window
         * this {@code EffectiveGrants} was resolved against.
         *
         * @param clock the caller's clock, for the {@code LocalDateTime}-to-{@code Instant}
         *     conversion; must use the same zone the assignments were resolved with
         * @return empty when no assignment in {@link #assignments()} carries an end date (every
         *     contributing grant is open-ended)
         */
        @NonNull
        public Optional<Instant> earliestAssignmentEnd(@NonNull Clock clock) {
            return assignments.stream()
                    .map(RoleAssignment::getEffectiveEndDate)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .map(end -> end.atZone(clock.getZone()).toInstant());
        }
    }
}
