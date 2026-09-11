package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleCreateRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.event.RoleAssignmentRevokedEvent;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.EffectiveGrantResolver.EffectiveGrants;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class RoleManagementServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private RoleAssignmentRepository roleAssignmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RolePersonaEventEmitter rolePersonaEventEmitter;

    @Mock
    private EffectiveGrantResolver effectiveGrantResolver;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private RoleManagementServiceImpl roleManagementService;

    @AfterEach
    void cleanupSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRole_andUpdatePermissions_success() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "agent-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        Role role = new Role();
        UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        role.setId(roleId);
        role.setName("MANAGER");

        Permission permission = new Permission();
        permission.setName("security:role:grant");

        when(roleRepository.existsByNameIgnoreCase("MANAGER")).thenReturn(false);
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(permissionRepository.findByName("security:role:grant")).thenReturn(Optional.of(permission));

        RoleDto created = roleManagementService.createRole(
                new RoleCreateRequest("MANAGER", "Manager role", null, null, null, null, null));
        RoleDto updated = roleManagementService.updateRolePermissions(
                new RolePermissionsRequest(roleId, Set.of("security:role:grant")));

        assertThat(created.getCreatedBy()).isEqualTo("agent-user");
        assertThat(updated.getPermissions()).extracting("name").contains("security:role:grant");
    }

    /** ADR-0062 §6 (WS8): a role loaded into the platform tenant joins the template under its own name. */
    @Test
    void provisionTemplateRole_createsTheRoleWithItsNameAsTemplateKey() {
        when(roleRepository.findByNameIgnoreCase("WARRANTY_CLERK")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoleDto created = roleManagementService.provisionTemplateRole(
                new RoleCreateRequest("WARRANTY_CLERK", "Warranty claim intake", null, null, null, null, null));

        assertThat(created.getTemplateKey()).isEqualTo("WARRANTY_CLERK");
        assertThat(created.getName()).isEqualTo("WARRANTY_CLERK");
        verify(rolePersonaEventEmitter).rolePersonaChanged(any(Role.class));
    }

    @Test
    void provisionTemplateRole_marksAnExistingUnmarkedRoleAndLeavesTheRestAlone() {
        Role role = new Role();
        role.setId(UUID.fromString("00000000-0000-0000-0000-000000000007"));
        role.setName("SHOP_MANAGER");
        role.setDescription("as the platform wrote it");
        when(roleRepository.findByNameIgnoreCase("SHOP_MANAGER")).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoleDto marked = roleManagementService.provisionTemplateRole(
                new RoleCreateRequest("SHOP_MANAGER", "a different description", null, null, null, null, null));

        assertThat(marked.getTemplateKey()).isEqualTo("SHOP_MANAGER");
        assertThat(role.getDescription()).isEqualTo("as the platform wrote it");
        verify(roleRepository).save(role);
        verify(rolePersonaEventEmitter, never()).rolePersonaChanged(any(Role.class));
    }

    /** Uniqueness is case-insensitive, so a differently-cased row is the same role: marked under its own name. */
    @Test
    void provisionTemplateRole_marksADifferentlyCasedExistingRoleUnderItsStoredName() {
        Role role = new Role();
        role.setId(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        role.setName("Shop_Manager");
        when(roleRepository.findByNameIgnoreCase("SHOP_MANAGER")).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoleDto marked = roleManagementService.provisionTemplateRole(
                new RoleCreateRequest("SHOP_MANAGER", null, null, null, null, null, null));

        assertThat(marked.getName()).isEqualTo("Shop_Manager");
        assertThat(marked.getTemplateKey()).isEqualTo("Shop_Manager");
        verify(roleRepository, never()).existsByNameIgnoreCase(any());
    }

    @Test
    void provisionTemplateRole_isANoOpForARoleAlreadyInTheTemplate() {
        Role role = new Role();
        role.setId(UUID.fromString("00000000-0000-0000-0000-000000000008"));
        role.setName("ADMIN");
        role.setTemplateKey("ADMIN");
        when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(role));

        RoleDto unchanged = roleManagementService.provisionTemplateRole(
                new RoleCreateRequest("ADMIN", null, null, null, null, null, null));

        assertThat(unchanged.getTemplateKey()).isEqualTo("ADMIN");
        verify(roleRepository, never()).save(any(Role.class));
    }

    @Test
    void createRoleAssignment_andPermissionChecks_success() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID assignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        User user = new User();
        user.setId(userId);
        user.setUsername("alice");

        Permission permission = new Permission();
        permission.setName("security:role:grant");

        Role role = new Role();
        role.setId(roleId);
        role.setName("MANAGER");
        role.setPermissions(Set.of(permission));

        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));

        RoleAssignmentRequest request = new RoleAssignmentRequest(
                userId, roleId, LocalDateTime.now(TEST_CLOCK).minusHours(1), null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(roleAssignmentRepository.findByUser_IdAndRole_Id(userId, roleId)).thenReturn(List.of());
        when(roleAssignmentRepository.save(any(RoleAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(effectiveGrantResolver.resolve(user))
                .thenReturn(new EffectiveGrants(Set.of(role), Set.of("MANAGER"), Set.of("security:role:grant")));
        when(roleAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));

        var createdAssignment = roleManagementService.createRoleAssignment(request);
        boolean hasPermission = roleManagementService.userHasPermission(userId, "security:role:grant");
        roleManagementService.revokeRoleAssignment(
                assignmentId, LocalDateTime.now(TEST_CLOCK).plusDays(1));

        assertThat(createdAssignment.getRoleId()).isEqualTo(roleId);
        assertThat(hasPermission).isTrue();
        // ADR-0061 §4 amendment (#1914 phase 3): revokeRoleAssignment ends the holder's live
        // tokens via RoleAssignmentRevokedEvent, even though endDate here is in the future.
        ArgumentCaptor<RoleAssignmentRevokedEvent> eventCaptor =
                ArgumentCaptor.forClass(RoleAssignmentRevokedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void getRoleByName_missingRole_throws() {
        when(roleRepository.findByName("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> roleManagementService.getRoleByName("MISSING"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Role not found");
    }
}
