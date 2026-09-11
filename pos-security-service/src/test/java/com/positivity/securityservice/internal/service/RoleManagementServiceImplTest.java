package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.domain.ReservedRoles;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleCreateRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.event.RoleAssignmentRevokedEvent;
import com.positivity.securityservice.internal.exception.RoleNotUserAssignableException;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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
    private RoleManagementServiceImpl.TemplateRoleProvisioner templateRoleProvisioner;

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

    /**
     * ADR-0062 §7: {@code PLATFORM_ADMIN} is the platform tenant's own role and may never join the
     * per-tenant role template, whatever its current {@code template_key} state — otherwise a
     * {@code roles.csv} row naming it would let provisioning and reconciliation copy its
     * {@code platform:*} grants into every tenant. This refusal stays directly in {@code
     * provisionTemplateRole}, ahead of {@link RoleManagementServiceImpl.TemplateRoleProvisioner}, so
     * a row naming it never opens a transaction at all.
     */
    @Test
    @DisplayName("provisionTemplateRole refuses PLATFORM_ADMIN by name, whether the role exists yet or not")
    void provisionTemplateRole_refusesPlatformAdminByName() {
        assertThatThrownBy(() -> roleManagementService.provisionTemplateRole(
                        new RoleCreateRequest(ReservedRoles.PLATFORM_ADMIN, null, null, null, null, null, null)))
                .isInstanceOf(SecurityValidationException.class)
                .hasMessageContaining(ReservedRoles.PLATFORM_ADMIN);
        verify(templateRoleProvisioner, never()).attempt(any(RoleCreateRequest.class));
    }

    /**
     * The transactional single-attempt logic (create, mark-existing, the platform-grant refusal) now
     * lives in {@link RoleManagementServiceImpl.TemplateRoleProvisioner}, a separate bean — see
     * {@code TemplateRoleProvisionerTest} for that behaviour and {@code
     * TemplateRoleProvisioningConcurrencyTest} for the real, transaction-boundary-crossing
     * concurrent-create race it exists to fix (Finding 1, Copilot review of PR #1955). What remains
     * here is {@code provisionTemplateRole}'s own retry loop: does it hand back the provisioner's
     * result, does it retry a collision, and does it give up after the configured attempt count —
     * exactly the shape {@code RoleTemplateReconciliationServiceTest} already tests for {@code
     * applyWithRetry}.
     */
    @Test
    @DisplayName("provisionTemplateRole returns the provisioner's result on the first attempt")
    void provisionTemplateRole_delegatesToTheProvisioner() throws Exception {
        RoleCreateRequest request =
                new RoleCreateRequest("WARRANTY_CLERK", "Warranty claim intake", null, null, null, null, null);
        Role provisioned = new Role();
        provisioned.setId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
        provisioned.setName("WARRANTY_CLERK");
        provisioned.setTemplateKey("WARRANTY_CLERK");
        when(templateRoleProvisioner.attempt(request)).thenReturn(provisioned);

        RoleDto result = roleManagementService.provisionTemplateRole(request);

        assertThat(result.getTemplateKey()).isEqualTo("WARRANTY_CLERK");
        verify(templateRoleProvisioner, times(1)).attempt(request);
    }

    @Test
    @DisplayName("provisionTemplateRole retries a collision in a fresh attempt and converges")
    void provisionTemplateRole_retriesOnCollisionAndConverges() throws Exception {
        RoleCreateRequest request =
                new RoleCreateRequest("WARRANTY_CLERK", "Warranty claim intake", null, null, null, null, null);
        Role winner = new Role();
        winner.setId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
        winner.setName("WARRANTY_CLERK");
        winner.setTemplateKey("WARRANTY_CLERK");
        when(templateRoleProvisioner.attempt(request))
                .thenThrow(new DataIntegrityViolationException("roles_tenant_lower_name_key"))
                .thenReturn(winner);

        RoleDto result = roleManagementService.provisionTemplateRole(request);

        assertThat(result.getTemplateKey()).isEqualTo("WARRANTY_CLERK");
        verify(templateRoleProvisioner, times(2)).attempt(request);
    }

    @Test
    @DisplayName("provisionTemplateRole gives up once every attempt collides")
    void provisionTemplateRole_givesUpAfterMaxAttempts() throws Exception {
        RoleCreateRequest request = new RoleCreateRequest("WARRANTY_CLERK", null, null, null, null, null, null);
        DataIntegrityViolationException collision = new DataIntegrityViolationException("roles_tenant_lower_name_key");
        when(templateRoleProvisioner.attempt(request)).thenThrow(collision);

        assertThatThrownBy(() -> roleManagementService.provisionTemplateRole(request))
                .isSameAs(collision);
        verify(templateRoleProvisioner, times(3)).attempt(request);
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
    @DisplayName("createRoleAssignment refuses SUPPORT: the dated-assignment path is a grant path too")
    void createRoleAssignment_supportRole_isRefused() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000012");

        User user = new User();
        user.setId(userId);
        user.setUsername("alice");

        Role support = new Role();
        support.setId(roleId);
        support.setName(ReservedRoles.SUPPORT);

        RoleAssignmentRequest request = new RoleAssignmentRequest(userId, roleId, null, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(support));

        assertThatThrownBy(() -> roleManagementService.createRoleAssignment(request))
                .isInstanceOf(RoleNotUserAssignableException.class)
                .hasMessageContaining(ReservedRoles.SUPPORT);

        verify(roleAssignmentRepository, never()).save(any(RoleAssignment.class));
    }

    @Test
    void getRoleByName_missingRole_throws() {
        when(roleRepository.findByName("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> roleManagementService.getRoleByName("MISSING"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Role not found");
    }

    /**
     * ADR-0062 §7 (Copilot review of PR #1955, Findings 2/3): {@code security:role:edit} authorizes
     * this method (directly, and through the role-permission bulk ingest that calls it per grant),
     * and nothing previously stopped it from handing a {@code platform:*} permission to a role that
     * is not {@code PLATFORM_ADMIN} in the platform tenant.
     */
    @Test
    @DisplayName("assignPermissionToRole refuses a platform:* permission on a role that is not PLATFORM_ADMIN")
    void assignPermissionToRole_refusesAPlatformPermissionOnAnOrdinaryRole() {
        UUID roleId = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        Role role = new Role();
        role.setId(roleId);
        role.setName("SHOP_MANAGER");
        Permission permission = new Permission();
        permission.setName("platform:tenant:create");
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(permissionRepository.findByName("platform:tenant:create")).thenReturn(Optional.of(permission));

        assertThatThrownBy(() -> roleManagementService.assignPermissionToRole(roleId, "platform:tenant:create"))
                .isInstanceOf(SecurityValidationException.class)
                .hasMessageContaining("platform:tenant:create");
        assertThat(role.getPermissions()).isEmpty();
        verify(roleRepository, never()).save(any(Role.class));
    }

    /** Same invariant, the other grant path: {@code updateRolePermissions} replaces the whole set directly. */
    @Test
    @DisplayName("updateRolePermissions refuses a platform:* permission on a role that is not PLATFORM_ADMIN")
    void updateRolePermissions_refusesAPlatformPermissionOnAnOrdinaryRole() {
        UUID roleId = UUID.fromString("00000000-0000-0000-0000-00000000000d");
        Role role = new Role();
        role.setId(roleId);
        role.setName("SHOP_MANAGER");
        Permission permission = new Permission();
        permission.setName("platform:tenant:create");
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(permissionRepository.findByName("platform:tenant:create")).thenReturn(Optional.of(permission));

        assertThatThrownBy(() -> roleManagementService.updateRolePermissions(
                        new RolePermissionsRequest(roleId, Set.of("platform:tenant:create"))))
                .isInstanceOf(SecurityValidationException.class)
                .hasMessageContaining("platform:tenant:create");
        assertThat(role.getPermissions()).isEmpty();
        verify(roleRepository, never()).save(any(Role.class));
    }
}
