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
    @DisplayName("unions user.getRoles() with the roles of the effective assignments")
    void resolve_unionsDirectRolesAndEffectiveAssignmentRoles() {
        Permission directPermission = permission("catalog:item:view");
        Role directRole = role("DIRECT_ROLE", directPermission);

        Permission assignedPermission = permission("catalog:item:edit");
        Role assignedRole = role("ASSIGNED_ROLE", assignedPermission);

        User user = new User();
        user.setRoles(Set.of(directRole));

        RoleAssignment assignment = new RoleAssignment();
        assignment.setRole(assignedRole);
        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(
                        user, LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone())))
                .thenReturn(List.of(assignment));

        EffectiveGrants grants = sut.resolve(user, NOW);

        assertThat(grants.roles()).containsExactlyInAnyOrder(directRole, assignedRole);
        assertThat(grants.roleNames()).containsExactlyInAnyOrder("DIRECT_ROLE", "ASSIGNED_ROLE");
        assertThat(grants.permissionNames()).containsExactlyInAnyOrder("catalog:item:view", "catalog:item:edit");
    }

    @Test
    @DisplayName("a role held both directly and via assignment contributes its permissions once")
    void resolve_overlappingRole_deduplicates() {
        Permission permission = permission("catalog:item:view");
        Role sharedRole = role("SHARED_ROLE", permission);

        User user = new User();
        user.setRoles(Set.of(sharedRole));

        RoleAssignment assignment = new RoleAssignment();
        assignment.setRole(sharedRole);
        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(
                        user, LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone())))
                .thenReturn(List.of(assignment));

        EffectiveGrants grants = sut.resolve(user, NOW);

        assertThat(grants.roles()).containsExactly(sharedRole);
        assertThat(grants.permissionNames()).containsExactly("catalog:item:view");
    }

    @Test
    @DisplayName("null-safe when the assignment repository answers null")
    void resolve_nullAssignments_returnsDirectRolesOnly() {
        Permission permission = permission("catalog:item:view");
        Role directRole = role("DIRECT_ROLE", permission);

        User user = new User();
        user.setRoles(Set.of(directRole));

        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(
                        user, LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone())))
                .thenReturn(null);

        EffectiveGrants grants = sut.resolve(user, NOW);

        assertThat(grants.roles()).containsExactly(directRole);
        assertThat(grants.roleNames()).containsExactly("DIRECT_ROLE");
        assertThat(grants.permissionNames()).containsExactly("catalog:item:view");
    }

    @Test
    @DisplayName("a user with no direct roles and no effective assignments resolves empty")
    void resolve_noGrants_returnsEmpty() {
        User user = new User();
        user.setRoles(Set.of());

        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(
                        user, LocalDateTime.ofInstant(NOW, TEST_CLOCK.getZone())))
                .thenReturn(List.of());

        EffectiveGrants grants = sut.resolve(user, NOW);

        assertThat(grants.roles()).isEmpty();
        assertThat(grants.roleNames()).isEmpty();
        assertThat(grants.permissionNames()).isEmpty();
    }

    @Test
    @DisplayName("resolve(User) evaluates at the injected clock's current instant")
    void resolve_withoutInstant_usesClock() {
        User user = new User();
        user.setRoles(Set.of());

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
