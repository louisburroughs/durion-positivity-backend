package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.service.EffectiveGrantResolver.EffectiveGrants;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-0061 amendment phase 2 (#1914): {@code role_assignments} is the only store of a user's
 * roles, so the resolver's former "union of {@code user.getRoles()} and effective assignments" is
 * now just the roles of the effective assignments themselves.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EffectiveGrantResolverImpl")
class EffectiveGrantResolverImplTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private RoleAssignmentRepository roleAssignmentRepository;

    private EffectiveGrantResolverImpl sut;

    @BeforeEach
    void setUp() {
        sut = new EffectiveGrantResolverImpl(roleAssignmentRepository, TEST_CLOCK);
    }

    @Test
    @DisplayName("resolves the roles, role names, and permission names of the effective assignments")
    void resolve_returnsRolesOfEffectiveAssignments() {
        Permission viewPermission = permission("catalog:item:view");
        Role viewRole = role("VIEW_ROLE", viewPermission);

        Permission editPermission = permission("catalog:item:edit");
        Role editRole = role("EDIT_ROLE", editPermission);

        User user = new User();

        RoleAssignment viewAssignment = new RoleAssignment();
        viewAssignment.setRole(viewRole);
        RoleAssignment editAssignment = new RoleAssignment();
        editAssignment.setRole(editRole);
        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(
                        user, LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone())))
                .thenReturn(List.of(viewAssignment, editAssignment));

        EffectiveGrants grants = sut.resolve(user, NOW);

        assertThat(grants.roles()).containsExactlyInAnyOrder(viewRole, editRole);
        assertThat(grants.roleNames()).containsExactlyInAnyOrder("VIEW_ROLE", "EDIT_ROLE");
        assertThat(grants.permissionNames()).containsExactlyInAnyOrder("catalog:item:view", "catalog:item:edit");
        assertThat(grants.assignments()).containsExactlyInAnyOrder(viewAssignment, editAssignment);
    }

    @Test
    @DisplayName("two assignments of the same role contribute its permissions once")
    void resolve_overlappingRole_deduplicates() {
        Permission permission = permission("catalog:item:view");
        Role sharedRole = role("SHARED_ROLE", permission);

        User user = new User();

        RoleAssignment first = new RoleAssignment();
        first.setRole(sharedRole);
        RoleAssignment second = new RoleAssignment();
        second.setRole(sharedRole);
        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(
                        user, LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone())))
                .thenReturn(List.of(first, second));

        EffectiveGrants grants = sut.resolve(user, NOW);

        assertThat(grants.roles()).containsExactly(sharedRole);
        assertThat(grants.permissionNames()).containsExactly("catalog:item:view");
        assertThat(grants.assignments()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("null-safe when the assignment repository answers null")
    void resolve_nullAssignments_returnsEmpty() {
        User user = new User();

        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(
                        user, LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone())))
                .thenReturn(null);

        EffectiveGrants grants = sut.resolve(user, NOW);

        assertThat(grants.roles()).isEmpty();
        assertThat(grants.roleNames()).isEmpty();
        assertThat(grants.permissionNames()).isEmpty();
        assertThat(grants.assignments()).isEmpty();
    }

    @Test
    @DisplayName("a user with no effective assignments resolves empty")
    void resolve_noAssignments_returnsEmpty() {
        User user = new User();

        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(
                        user, LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone())))
                .thenReturn(List.of());

        EffectiveGrants grants = sut.resolve(user, NOW);

        assertThat(grants.roles()).isEmpty();
        assertThat(grants.roleNames()).isEmpty();
        assertThat(grants.permissionNames()).isEmpty();
        assertThat(grants.assignments()).isEmpty();
    }

    @Test
    @DisplayName("resolve(User) evaluates at the injected clock's current instant")
    void resolve_withoutInstant_usesClock() {
        User user = new User();

        ArgumentCaptor<LocalDateTime> asOfCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(ArgumentMatchers.eq(user), asOfCaptor.capture()))
                .thenReturn(List.of());

        sut.resolve(user);

        assertThat(asOfCaptor.getValue()).isEqualTo(LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone()));
    }

    private static Role role(String name, Permission... permissions) {
        Role role = new Role();
        role.setName(name);
        role.setPermissions(Set.of(permissions));
        return role;
    }

    private static Permission permission(String name) {
        Permission permission = new Permission();
        permission.setName(name);
        return permission;
    }
}
