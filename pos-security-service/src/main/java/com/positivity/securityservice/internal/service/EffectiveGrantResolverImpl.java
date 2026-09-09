package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * See {@link EffectiveGrantResolver} for why this exists (ADR-0061 amendment, 2026-09-09, #1914).
 *
 * <p>{@code asOf} is converted to the {@link LocalDateTime} {@code
 * RoleAssignmentRepository.findEffectiveAssignmentsByUser} takes the same way every other caller
 * did before this type existed: {@code LocalDateTime.ofInstant(asOf, clock.getZone())}, so a
 * fixed-zone test clock and the production {@code Clock.systemUTC()} (#1915) both place the
 * query's bound parameter on the caller's zone rather than the JVM default one.
 */
@Service
@RequiredArgsConstructor
public class EffectiveGrantResolverImpl implements EffectiveGrantResolver {

    private final RoleAssignmentRepository roleAssignmentRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public @NonNull EffectiveGrants resolve(@NonNull User user, @NonNull Instant asOf) {
        Set<Role> roles = new HashSet<>(user.getRoles());

        List<RoleAssignment> effectiveAssignments = roleAssignmentRepository.findEffectiveAssignmentsByUser(
                user, LocalDateTime.ofInstant(asOf, clock.getZone()));
        if (effectiveAssignments != null) {
            effectiveAssignments.forEach(assignment -> roles.add(assignment.getRole()));
        }

        Set<String> roleNames = roles.stream().map(Role::getName).collect(Collectors.toSet());
        Set<String> permissionNames = roles.stream()
                .map(Role::getPermissions)
                .flatMap(Set::stream)
                .map(Permission::getName)
                .collect(Collectors.toSet());

        return new EffectiveGrants(roles, roleNames, permissionNames);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull EffectiveGrants resolve(@NonNull User user) {
        return resolve(user, Instant.now(clock));
    }
}
