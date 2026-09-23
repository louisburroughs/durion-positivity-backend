package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.EmployeeRoleAssignmentDto;
import com.positivity.people.internal.entity.ExtRoleAssignmentReplica;
import com.positivity.people.internal.repository.ExtRoleAssignmentReplicaRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RoleAssignmentReplicaService} backed by the {@code ext_role_assignment_replica} replica
 * (ADR-0044 §6, durion#2155/#2160): pos-security-service owns role assignments; this module reads
 * its event-fed copy.
 */
@Service
@Transactional(readOnly = true)
public class RoleAssignmentReplicaServiceImpl implements RoleAssignmentReplicaService {

    private final ExtRoleAssignmentReplicaRepository repository;
    private final Clock clock;

    public RoleAssignmentReplicaServiceImpl(
            @NonNull ExtRoleAssignmentReplicaRepository repository, @NonNull Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @NonNull
    public Map<String, List<EmployeeRoleAssignmentDto>> findActiveRoleAssignmentsByUsernames(
            @NonNull Collection<String> usernames) {
        if (usernames.isEmpty()) {
            // Guards the round trip, not just correctness: `IN ()` is invalid SQL, and a caller
            // whose page has no resolvable username (e.g. no employees) should not pay for it.
            return Map.of();
        }
        List<ExtRoleAssignmentReplica> active = repository.findActiveByUsernameIn(usernames, LocalDateTime.now(clock));

        // LinkedHashMap/mutable lists: the repository query already orders by username, but the
        // grouping collector's map/list types are otherwise unspecified, and a stable iteration
        // order is worth keeping for a page render.
        Map<String, List<EmployeeRoleAssignmentDto>> byUsername = new LinkedHashMap<>();
        for (ExtRoleAssignmentReplica assignment : active) {
            byUsername
                    .computeIfAbsent(assignment.getUsername(), ignored -> new ArrayList<>())
                    .add(toDto(assignment));
        }
        // Deterministic per-username ordering (by role name) so the same page renders identically
        // across requests regardless of replica insertion order.
        byUsername.values().forEach(list -> list.sort(Comparator.comparing(EmployeeRoleAssignmentDto::getRoleName)));
        return byUsername;
    }

    private EmployeeRoleAssignmentDto toDto(ExtRoleAssignmentReplica assignment) {
        return EmployeeRoleAssignmentDto.builder()
                .assignmentId(assignment.getAssignmentId())
                .roleId(assignment.getRoleId())
                .roleName(assignment.getRoleName())
                .roleLocationScope(assignment.getRoleLocationScope())
                .effectiveStartDate(assignment.getEffectiveStartDate())
                .effectiveEndDate(assignment.getEffectiveEndDate())
                .revokedAt(assignment.getRevokedAt())
                .build();
    }
}
