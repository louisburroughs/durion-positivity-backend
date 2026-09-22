package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.EmployeeRoleAssignmentDto;
import com.positivity.people.internal.entity.ExtRoleAssignmentReplica;
import com.positivity.people.internal.repository.ExtRoleAssignmentReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RoleAssignmentReplicaServiceImpl} (durion#2155/#2160): the batched
 * username lookup the employee register's projection calls once per page rather than once per
 * row.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleAssignmentReplicaServiceImpl — batched active-assignment lookup")
class RoleAssignmentReplicaServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Mock
    private ExtRoleAssignmentReplicaRepository repository;

    private RoleAssignmentReplicaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoleAssignmentReplicaServiceImpl(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExtRoleAssignmentReplica assignment(String username, UUID roleId, String roleName) {
        return ExtRoleAssignmentReplica.builder()
                .assignmentId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .username(username)
                .roleId(roleId)
                .roleName(roleName)
                .roleLocationScope("ALL")
                .effectiveStartDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .aggregateVersion(1)
                .build();
    }

    @Test
    @DisplayName("issues one repository call for the whole batch of usernames, not one per username")
    void batchesIntoASingleRepositoryCall() {
        when(repository.findActiveByUsernameIn(any(), any())).thenReturn(List.of());

        service.findActiveRoleAssignmentsByUsernames(Set.of("ada", "grace", "hedy"));

        verify(repository).findActiveByUsernameIn(eq(Set.of("ada", "grace", "hedy")), eq(LocalDateTime.now(Clock.fixed(NOW, ZoneOffset.UTC))));
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("groups results by username and projects to the DTO")
    void groupsResultsByUsername() {
        UUID roleId = UUID.randomUUID();
        when(repository.findActiveByUsernameIn(any(), any()))
                .thenReturn(List.of(
                        assignment("ada", roleId, "SHOP_MANAGER"), assignment("grace", roleId, "TECHNICIAN")));

        Map<String, List<EmployeeRoleAssignmentDto>> result =
                service.findActiveRoleAssignmentsByUsernames(Set.of("ada", "grace", "hedy"));

        assertThat(result).containsOnlyKeys("ada", "grace");
        assertThat(result.get("ada")).hasSize(1);
        assertThat(result.get("ada").get(0).getRoleName()).isEqualTo("SHOP_MANAGER");
        assertThat(result.get("ada").get(0).getRoleId()).isEqualTo(roleId);
        assertThat(result.get("ada").get(0).getRoleLocationScope()).isEqualTo("ALL");
        // A username with no active assignment is absent, not mapped to an empty list.
        assertThat(result).doesNotContainKey("hedy");
    }

    @Test
    @DisplayName("collects every assignment for a username that holds more than one role")
    void collectsMultipleAssignmentsPerUsername() {
        when(repository.findActiveByUsernameIn(any(), any()))
                .thenReturn(List.of(
                        assignment("ada", UUID.randomUUID(), "SHOP_MANAGER"),
                        assignment("ada", UUID.randomUUID(), "PARTS_COUNTER")));

        Map<String, List<EmployeeRoleAssignmentDto>> result =
                service.findActiveRoleAssignmentsByUsernames(Set.of("ada"));

        assertThat(result.get("ada")).extracting(EmployeeRoleAssignmentDto::getRoleName)
                .containsExactly("PARTS_COUNTER", "SHOP_MANAGER");
    }

    @Test
    @DisplayName("skips the repository round trip entirely for an empty batch")
    void emptyBatchSkipsRepositoryCall() {
        Map<String, List<EmployeeRoleAssignmentDto>> result = service.findActiveRoleAssignmentsByUsernames(Set.of());

        assertThat(result).isEmpty();
        verify(repository, never()).findActiveByUsernameIn(any(), any());
    }
}
