package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.ScopeType;
import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;

/**
 * Story #62 RED unit tests for {@link RoleManagementServiceImpl}.
 *
 * <p>
 * Covers all RBAC management acceptance criteria from Story #62:
 * <ol>
 * <li>Create role: valid name succeeds, returns created role with ID —
 * <strong>GREEN</strong></li>
 * <li>Create role: duplicate (case-insensitive) throws
 * {@link DuplicateRoleNameException} — <strong>RED</strong></li>
 * <li>Get role by ID: returns role — <strong>RED</strong> (UOE stub)</li>
 * <li>Get role by ID: not found returns empty Optional — <strong>RED</strong>
 * (UOE stub)</li>
 * <li>List all roles: returns list — <strong>GREEN</strong></li>
 * <li>Delete role: cascade-removes associations — <strong>RED</strong> (UOE
 * stub)</li>
 * <li>Assign permission to role: succeeds — <strong>RED</strong> (UOE
 * stub)</li>
 * <li>Assign permission to role: non-existent permission throws
 * {@link PermissionNotFoundException} — <strong>RED</strong></li>
 * <li>Revoke permission from role: succeeds — <strong>RED</strong> (UOE
 * stub)</li>
 * <li>Assign role to user: succeeds — <strong>RED</strong> (UOE stub)</li>
 * <li>Revoke role from user: succeeds — <strong>RED</strong> (UOE stub)</li>
 * <li>Effective permissions = union of all role permissions —
 * <strong>GREEN</strong></li>
 * </ol>
 *
 * <p>
 * Tests marked <strong>RED</strong> fail because the target stub throws
 * {@link UnsupportedOperationException}. They will turn GREEN when the business
 * logic is implemented.
 *
 * <p>
 * Maps to ADR-0018 (actor from security context) for mutation operations.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleManagementServiceTest — Story #62")
class RoleManagementServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ROLE_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

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

    @InjectMocks
    private RoleManagementServiceImpl sut;

    @AfterEach
    void cleanupSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── AC 1: Create role ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("createRole() — AC 1")
    class CreateRole {

        /**
         * AC 1: A valid role name succeeds and returns a RoleDto with a non-null ID.
         *
         * <p>
         * Story mapping: Functional Behavior 1 — create role.
         * Status: <strong>GREEN</strong> — {@code createRole} impl already exists.
         */
        @Test
        @DisplayName("valid name returns created role with non-null ID and actor from context")
        void createRole_validName_returnsCreatedRoleWithId() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "admin-user", "n/a",
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

            Role saved = new Role();
            saved.setId(ROLE_ID);
            saved.setName("ShopManager");
            saved.setDescription("Manages the shop floor");
            saved.setCreatedBy("admin-user");
            saved.setPermissions(new HashSet<>());
            when(roleRepository.existsByNameIgnoreCase("ShopManager")).thenReturn(false);
            when(roleRepository.save(any(Role.class))).thenReturn(saved);

            RoleDto result = sut.createRole("ShopManager", "Manages the shop floor");

            assertThat(result.getId()).isEqualTo(ROLE_ID);
            assertThat(result.getName()).isEqualTo("ShopManager");
            assertThat(result.getCreatedBy()).isEqualTo("admin-user");
        }

        /**
         * AC: Duplicate role name (case-insensitive) throws
         * {@link DuplicateRoleNameException}.
         *
         * <p>
         * Story mapping: Business Rule — role names are case-insensitively unique.
         */
        @Test
        @DisplayName("duplicate name (case-insensitive) throws DuplicateRoleNameException → 409")
        void createRole_caseInsensitiveDuplicate_throwsDuplicateRoleNameException() {
            // "shopmanager" (lowercase) is a case-insensitive duplicate of an existing
            // "ShopManager"
            when(roleRepository.existsByNameIgnoreCase("shopmanager")).thenReturn(true);

            assertThatThrownBy(() -> sut.createRole("shopmanager", null))
                    .isInstanceOf(DuplicateRoleNameException.class)
                    .hasMessageContaining("shopmanager");
        }
    }

    // ── AC: Get role by ID ────────────────────────────────────────────────────

    @Nested
    @DisplayName("getRoleById() — Story #62 get-by-UUID")
    class GetRoleById {

        /**
         * Story mapping: Functional Behavior 1 — view a specific role by UUID.
         *
         * <p>
         * <strong>RED</strong>: {@code getRoleById} is a Story #62 stub that throws
         * {@link UnsupportedOperationException}.
         *
         * <p>
         * Expected failure: {@code UnsupportedOperationException} with message
         * "Story #62: getRoleById not yet implemented".
         */
        @Test
        @DisplayName("existing UUID — returns RoleDto")
        void getRoleById_existingId_returnsRole() {
            Role role = new Role();
            role.setId(ROLE_ID);
            role.setName("ShopManager");
            role.setPermissions(new HashSet<>());
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));

            Optional<RoleDto> result = sut.getRoleById(ROLE_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(ROLE_ID);
            assertThat(result.get().getName()).isEqualTo("ShopManager");
        }

        /**
         * Story mapping: Alternate Flow — role not found by UUID.
         *
         * <p>
         * <strong>RED</strong>: Same UOE stub as above.
         */
        @Test
        @DisplayName("non-existent UUID — returns empty Optional")
        void getRoleById_nonExistentId_returnsEmpty() {
            UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000099");
            when(roleRepository.findById(unknown)).thenReturn(Optional.empty());

            Optional<RoleDto> result = sut.getRoleById(unknown);

            assertThat(result).isEmpty();
        }
    }

    // ── AC: List roles ────────────────────────────────────────────────────────

    /**
     * Story mapping: Functional Behavior 1 — view a list of all existing Roles.
     *
     * <p>
     * Status: <strong>GREEN</strong> — {@code getAllRoles} impl already exists.
     */
    @Test
    @DisplayName("getAllRoles() returns all roles — GREEN")
    void getAllRoles_returnsAllRoles() {
        Role r1 = new Role();
        r1.setId(ROLE_ID);
        r1.setName("ReadOnlyScheduler");
        r1.setPermissions(new HashSet<>());
        Role r2 = new Role();
        r2.setId(ROLE_ID_2);
        r2.setName("Dispatcher");
        r2.setPermissions(new HashSet<>());
        when(roleRepository.findAll()).thenReturn(List.of(r1, r2));

        List<RoleDto> result = sut.getAllRoles();

        assertThat(result)
                .hasSize(2)
                .extracting(RoleDto::getName)
                .containsExactlyInAnyOrder("ReadOnlyScheduler", "Dispatcher");
    }

    // ── AC: Delete role (cascade) ─────────────────────────────────────────────

    /**
     * Story mapping: Functional Behavior 1 — "delete a Role and remove all
     * associated user-role and role-permission assignments".
     *
     * <p>
     * <strong>RED</strong>: {@code deleteRole} is a Story #62 stub throwing
     * {@link UnsupportedOperationException}.
     *
     * <p>
     * Expected failure: test asserts
     * {@code verify(roleRepository).deleteById(ROLE_ID)}
     * but the stub throws UOE before any repository call is made.
     */
    @Test
    @DisplayName("deleteRole() cascades to role_permission and user_role")
    void deleteRole_cascadesToAssociations() {
        Role role = new Role();
        role.setId(ROLE_ID);
        role.setName("Dispatcher");
        role.setPermissions(new HashSet<>());
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));

        sut.deleteRole(ROLE_ID);

        // GREEN impl must: delete role-permission records, delete user-role records,
        // then delete role
        verify(roleRepository).deleteById(ROLE_ID);
    }

    // ── AC: Assign permission to role ─────────────────────────────────────────

    @Nested
    @DisplayName("assignPermissionToRole() — Story #62 assign-by-key")
    class AssignPermission {

        /**
         * Story mapping: Functional Behavior 2 — "assign one or more predefined
         * Permissions to a Role".
         *
         * <p>
         * <strong>RED</strong>: {@code assignPermissionToRole} is a stub throwing UOE.
         */
        @Test
        @DisplayName("existing role and permission — completes without exception")
        void assignPermissionToRole_succeeds() {
            String permissionKey = "security:roles:create";
            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(new HashSet<>());
            Permission perm = new Permission();
            perm.setName(permissionKey);
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(permissionRepository.findByName(permissionKey)).thenReturn(Optional.of(perm));

            // GREEN impl returns normally; stub throws UOE
            sut.assignPermissionToRole(ROLE_ID, permissionKey);
        }

        /**
         * Story mapping: Alternate Flow — "Assigning Non-Existent Permission must
         * fail".
         *
         * <p>
         * <strong>RED</strong>: The stub throws {@link UnsupportedOperationException}
         * but the test asserts {@link PermissionNotFoundException}. Once the
         * implementation
         * exists the correct exception will be thrown.
         */
        @Test
        @DisplayName("non-existent permission key throws PermissionNotFoundException")
        void assignPermissionToRole_nonExistentPermission_throwsPermissionNotFoundException() {
            String permissionKey = "non:existent:permission";
            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(new HashSet<>());
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(permissionRepository.findByName(permissionKey)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.assignPermissionToRole(ROLE_ID, permissionKey))
                    .isInstanceOf(PermissionNotFoundException.class);
        }
    }

    // ── AC: Revoke permission from role ───────────────────────────────────────

    /**
     * Story mapping: Functional Behavior 2 — "remove one or more Permissions from a
     * Role".
     *
     * <p>
     * <strong>RED</strong>: {@code revokePermissionFromRole} is a Story #62 stub.
     */
    @Test
    @DisplayName("revokePermissionFromRole() completes without exception")
    void revokePermissionFromRole_succeeds() {
        String permissionKey = "security:roles:create";
        Role role = new Role();
        role.setId(ROLE_ID);
        Permission perm = new Permission();
        perm.setName(permissionKey);
        role.setPermissions(new HashSet<>(Set.of(perm)));
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));

        // GREEN impl removes the permission from the role; stub throws UOE
        sut.revokePermissionFromRole(ROLE_ID, permissionKey);
    }

    // ── AC: Assign / revoke role on user ──────────────────────────────────────

    @Nested
    @DisplayName("assignRoleToUser() / revokeRoleFromUser() — Story #62 user-role management")
    class UserRoleManagement {

        /**
         * Story mapping: Functional Behavior 3 — "assign one or more Roles to a
         * specific User".
         *
         * <p>
         * <strong>RED</strong>: {@code assignRoleToUser} is a Story #62 stub.
         */
        @Test
        @DisplayName("assignRoleToUser() completes without exception")
        void assignRoleToUser_succeeds() {
            User user = new User();
            user.setId(USER_ID);
            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(new HashSet<>());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));

            sut.assignRoleToUser(USER_ID, ROLE_ID);

            verify(roleAssignmentRepository).save(any(RoleAssignment.class));
        }

        /**
         * Story mapping: Functional Behavior 3 — "remove one or more Roles from a
         * specific User".
         *
         * <p>
         * <strong>RED</strong>: {@code revokeRoleFromUser} is a Story #62 stub.
         */
        @Test
        @DisplayName("revokeRoleFromUser() completes without exception")
        void revokeRoleFromUser_succeeds() {
            User user = new User();
            user.setId(USER_ID);
            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(new HashSet<>());
            RoleAssignment assignment = new RoleAssignment();
            assignment.setUser(user);
            assignment.setRole(role);
            assignment.setScopeType(ScopeType.GLOBAL);
            assignment.setEffectiveStartDate(java.time.LocalDateTime.now(TEST_CLOCK).minusDays(1));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUserAndRole(user, role)).thenReturn(List.of(assignment));

            sut.revokeRoleFromUser(USER_ID, ROLE_ID);

            verify(roleAssignmentRepository).save(assignment);
        }
    }

    // ── AC 2: Effective permissions ───────────────────────────────────────────

    /**
     * AC 2: Effective permissions are the union of all permissions granted by all
     * roles assigned to the user.
     *
     * <p>
     * Story mapping: AC 2 — "effective permissions are the union of all
     * permissions granted by all roles assigned to a user".
     *
     * <p>
     * Status: <strong>GREEN</strong> — {@code getUserPermissions} impl already
     * exists
     * and performs the union correctly.
     */
    @Test
    @DisplayName("getUserPermissions() returns union of all role permissions — GREEN")
    void getEffectivePermissionsForUser_returnsUnionOfAllRolePermissions() {
        User user = new User();
        user.setId(USER_ID);

        Permission p1 = new Permission();
        p1.setName("security:roles:create");
        Permission p2 = new Permission();
        p2.setName("security:users:view");

        Role role1 = new Role();
        role1.setId(ROLE_ID);
        role1.setPermissions(Set.of(p1));
        Role role2 = new Role();
        role2.setId(ROLE_ID_2);
        role2.setPermissions(Set.of(p2));

        RoleAssignment ra1 = new RoleAssignment();
        ra1.setUser(user);
        ra1.setRole(role1);
        ra1.setScopeType(ScopeType.GLOBAL);
        ra1.setEffectiveStartDate(java.time.LocalDateTime.now(TEST_CLOCK).minusDays(1));

        RoleAssignment ra2 = new RoleAssignment();
        ra2.setUser(user);
        ra2.setRole(role2);
        ra2.setScopeType(ScopeType.GLOBAL);
        ra2.setEffectiveStartDate(java.time.LocalDateTime.now(TEST_CLOCK).minusDays(1));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(roleAssignmentRepository.findEffectiveAssignmentsByUser(user))
                .thenReturn(List.of(ra1, ra2));

        Set<PermissionDto> permissions = sut.getUserPermissions(USER_ID);

        assertThat(permissions)
                .extracting(PermissionDto::getName)
                .containsExactlyInAnyOrder("security:roles:create", "security:users:view");
    }
}
