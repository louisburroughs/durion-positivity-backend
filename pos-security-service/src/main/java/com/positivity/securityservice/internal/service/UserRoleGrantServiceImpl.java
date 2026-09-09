package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * See {@link UserRoleGrantService} for why this exists (ADR-0061 amendment, 2026-09-09, #1914
 * phase 2).
 *
 * <p>Depends only on {@link RoleAssignmentRepository} and {@link Clock}, deliberately: every
 * writer that needs this (role management, user provisioning, self-registration) already depends
 * on this service, and a dependency back onto any of them would be circular.
 */
@Service
@RequiredArgsConstructor
public class UserRoleGrantServiceImpl implements UserRoleGrantService {

    private final RoleAssignmentRepository roleAssignmentRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void grant(@NonNull User user, @NonNull Role role, @NonNull String actor) {
        LocalDateTime now = LocalDateTime.now(clock);
        boolean alreadyEffective = roleAssignmentRepository.findByUserAndRole(user, role).stream()
                .anyMatch(assignment -> assignment.isEffectiveAt(now));
        if (alreadyEffective) {
            return;
        }

        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(now);
        assignment.setCreatedBy(actor);
        assignment.setCreatedAt(Instant.now(clock));
        roleAssignmentRepository.save(assignment);
    }

    @Override
    @Transactional
    public void revoke(@NonNull User user, @NonNull Role role, @NonNull String actor) {
        LocalDateTime now = LocalDateTime.now(clock);
        revokeEffective(roleAssignmentRepository.findByUserAndRole(user, role), now, actor);
    }

    @Override
    @Transactional
    public void reconcile(@NonNull User user, @NonNull Set<Role> desiredRoles, @NonNull String actor) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<RoleAssignment> existing = roleAssignmentRepository.findByUser(user);

        Set<UUID> desiredRoleIds = desiredRoles.stream().map(Role::getId).collect(Collectors.toSet());
        Set<UUID> effectiveRoleIds = existing.stream()
                .filter(assignment -> assignment.isEffectiveAt(now))
                .map(assignment -> assignment.getRole().getId())
                .collect(Collectors.toSet());

        List<RoleAssignment> toRevoke = existing.stream()
                .filter(assignment -> assignment.isEffectiveAt(now))
                .filter(assignment ->
                        !desiredRoleIds.contains(assignment.getRole().getId()))
                .toList();
        revokeEffective(toRevoke, now, actor);

        for (Role role : desiredRoles) {
            if (!effectiveRoleIds.contains(role.getId())) {
                grant(user, role, actor);
            }
        }
    }

    private void revokeEffective(List<RoleAssignment> candidates, LocalDateTime now, String actor) {
        for (RoleAssignment assignment : candidates) {
            if (!assignment.isEffectiveAt(now)) {
                continue;
            }
            assignment.revoke(now, Instant.now(clock));
            assignment.setLastModifiedBy(actor);
            assignment.setLastModifiedAt(Instant.now(clock));
            roleAssignmentRepository.save(assignment);
        }
    }
}
