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
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.ScopeType;
import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.exception.RoleAssignmentNotFoundException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.time.LocalDateTime;

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
 * <li>Assign permission to role: succeeds — <strong>GREEN</strong></li>
 * <li>Assign permission to role: non-existent permission throws
 * {@link PermissionNotFoundException} — <strong>RED</strong></li>
 * <li>Revoke permission from role: succeeds — <strong>GREEN</strong></li>
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
         */
        @Test
        @DisplayName("existing role and permission — completes without exception")
        void assignPermissionToRole_succeeds() {
            String permissionKey = "security:roles:create";
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "rbac-admin", "n/a",
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(new HashSet<>());
            Permission perm = new Permission();
            perm.setName(permissionKey);
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(permissionRepository.findByName(permissionKey)).thenReturn(Optional.of(perm));
            when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

            sut.assignPermissionToRole(ROLE_ID, permissionKey);

            assertThat(role.getPermissions())
                    .extracting(Permission::getName)
                    .contains(permissionKey);
            assertThat(role.getLastModifiedBy()).isEqualTo("rbac-admin");
            assertThat(role.getLastModifiedAt()).isEqualTo(Instant.now(TEST_CLOCK));
            verify(roleRepository).save(role);
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
     */
    @Test
    @DisplayName("revokePermissionFromRole() completes without exception")
    void revokePermissionFromRole_succeeds() {
        String permissionKey = "security:roles:create";
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "rbac-admin", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        Role role = new Role();
        role.setId(ROLE_ID);
        Permission perm = new Permission();
        perm.setName(permissionKey);
        role.setPermissions(new HashSet<>(Set.of(perm)));
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));

        sut.revokePermissionFromRole(ROLE_ID, permissionKey);

        assertThat(role.getPermissions())
                .extracting(Permission::getName)
                .doesNotContain(permissionKey);
        assertThat(role.getLastModifiedBy()).isEqualTo("rbac-admin");
        assertThat(role.getLastModifiedAt()).isEqualTo(Instant.now(TEST_CLOCK));
        verify(roleRepository).save(role);
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
        @DisplayName("assignRoleToUser() with unknown userId throws UserNotFoundException")
        void assignRoleToUser_userNotFound_throwsUserNotFoundException() {
            UUID unknownUserId = UUID.fromString("00000000-0000-0000-0000-000000000099");
            when(userRepository.findById(unknownUserId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.assignRoleToUser(unknownUserId, ROLE_ID))
                    .isInstanceOf(UserNotFoundException.class);
        }

        @Test
        @DisplayName("assignRoleToUser() with unknown roleId throws RoleNotFoundException")
        void assignRoleToUser_roleNotFound_throwsRoleNotFoundException() {
            User user = new User();
            user.setId(USER_ID);
            UUID unknownRoleId = UUID.fromString("00000000-0000-0000-0000-000000000099");
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(unknownRoleId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.assignRoleToUser(USER_ID, unknownRoleId))
                    .isInstanceOf(RoleNotFoundException.class);
        }

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
        @DisplayName("revokeRoleFromUser() with unknown userId throws UserNotFoundException")
        void revokeRoleFromUser_userNotFound_throwsUserNotFoundException() {
            UUID unknownUserId = UUID.fromString("00000000-0000-0000-0000-000000000099");
            when(userRepository.findById(unknownUserId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.revokeRoleFromUser(unknownUserId, ROLE_ID))
                    .isInstanceOf(UserNotFoundException.class);
        }

        @Test
        @DisplayName("revokeRoleFromUser() with unknown roleId throws RoleNotFoundException")
        void revokeRoleFromUser_roleNotFound_throwsRoleNotFoundException() {
            User user = new User();
            user.setId(USER_ID);
            UUID unknownRoleId = UUID.fromString("00000000-0000-0000-0000-000000000099");
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(unknownRoleId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.revokeRoleFromUser(USER_ID, unknownRoleId))
                    .isInstanceOf(RoleNotFoundException.class);
        }

        @Test
        @DisplayName("revokeRoleFromUser() with no active assignment throws RoleAssignmentNotFoundException")
        void revokeRoleFromUser_noActiveAssignment_throwsRoleAssignmentNotFoundException() {
            User user = new User();
            user.setId(USER_ID);
            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(new HashSet<>());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUserAndRole(user, role)).thenReturn(List.of());

            assertThatThrownBy(() -> sut.revokeRoleFromUser(USER_ID, ROLE_ID))
                    .isInstanceOf(RoleAssignmentNotFoundException.class);
        }

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

    // ── updateRolePermissions error paths ────────────────────────────────────

    @Nested
    @DisplayName("updateRolePermissions() — error paths")
    class UpdateRolePermissions {

        @Test
        @DisplayName("unknown roleId throws RoleNotFoundException")
        void updateRolePermissions_roleNotFound_throwsRoleNotFoundException() {
            UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000099");
            when(roleRepository.findById(unknownId)).thenReturn(Optional.empty());
            RolePermissionsRequest request = new RolePermissionsRequest(unknownId, Set.of("perm:x"));

            assertThatThrownBy(() -> sut.updateRolePermissions(request))
                    .isInstanceOf(RoleNotFoundException.class);
        }

        @Test
        @DisplayName("unknown permissionName throws PermissionNotFoundException")
        void updateRolePermissions_permissionNotFound_throwsPermissionNotFoundException() {
            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(new HashSet<>());
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(permissionRepository.findByName("unknown:perm")).thenReturn(Optional.empty());
            RolePermissionsRequest request = new RolePermissionsRequest(ROLE_ID, Set.of("unknown:perm"));

            assertThatThrownBy(() -> sut.updateRolePermissions(request))
                    .isInstanceOf(PermissionNotFoundException.class);
        }
    }

    // ── deleteRole error path ─────────────────────────────────────────────────

    @Test
    @DisplayName("deleteRole() with unknown ID throws RoleNotFoundException")
    void deleteRole_roleNotFound_throwsRoleNotFoundException() {
        UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(roleRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.deleteRole(unknownId))
                .isInstanceOf(RoleNotFoundException.class);
    }

    // ── revokePermissionFromRole error path ───────────────────────────────────

    @Test
    @DisplayName("revokePermissionFromRole() with unknown roleId throws RoleNotFoundException")
    void revokePermissionFromRole_roleNotFound_throwsRoleNotFoundException() {
        UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(roleRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.revokePermissionFromRole(unknownId, "perm:x"))
                .isInstanceOf(RoleNotFoundException.class);
    }

    // ── getRoleByName ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getRoleByName() with existing name returns RoleDto")
    void getRoleByName_existingName_returnsRole() {
        Role role = new Role();
        role.setId(ROLE_ID);
        role.setName("ShopManager");
        role.setPermissions(new HashSet<>());
        when(roleRepository.findByName("ShopManager")).thenReturn(Optional.of(role));

        RoleDto result = sut.getRoleByName("ShopManager");

        assertThat(result.getName()).isEqualTo("ShopManager");
        assertThat(result.getId()).isEqualTo(ROLE_ID);
    }

    @Test
    @DisplayName("getRoleByName() with unknown name throws RoleNotFoundException")
    void getRoleByName_notFound_throwsRoleNotFoundException() {
        when(roleRepository.findByName("Unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.getRoleByName("Unknown"))
                .isInstanceOf(RoleNotFoundException.class);
    }

    // ── revokeRoleAssignment error path ───────────────────────────────────────

    @Nested
    @DisplayName("revokeRoleAssignment() — error paths")
    class RevokeRoleAssignment {

        @Test
        @DisplayName("unknown assignmentId throws RoleAssignmentNotFoundException")
        void revokeRoleAssignment_notFound_throwsException() {
            UUID unknownAssignmentId = UUID.fromString("00000000-0000-0000-0000-000000000099");
            when(roleAssignmentRepository.findById(unknownAssignmentId)).thenReturn(Optional.empty());
            LocalDateTime endDate = LocalDateTime.now(TEST_CLOCK);

            assertThatThrownBy(() -> sut.revokeRoleAssignment(unknownAssignmentId, endDate))
                    .isInstanceOf(RoleAssignmentNotFoundException.class);
        }
    }

    // ── getAssignmentsForUser — includeHistory ────────────────────────────────

    @Test
    @DisplayName("getAssignmentsForUser() with includeHistory=true returns all assignments")
    void getAssignmentsForUser_includeHistory_returnsAllAssignments() {
        User user = new User();
        user.setId(USER_ID);

        Role role = new Role();
        role.setId(ROLE_ID);
        role.setPermissions(new HashSet<>());

        RoleAssignment past = new RoleAssignment();
        past.setUser(user);
        past.setRole(role);
        past.setScopeType(ScopeType.GLOBAL);
        past.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(10));
        // effectiveEndDate already set (in the past)

        RoleAssignment current = new RoleAssignment();
        current.setUser(user);
        current.setRole(role);
        current.setScopeType(ScopeType.GLOBAL);
        current.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(roleAssignmentRepository.findAllByUser_Id(USER_ID)).thenReturn(List.of(past, current));

        List<RoleAssignmentDto> result = sut.getAssignmentsForUser(USER_ID, true);

        assertThat(result).hasSize(2);
    }

    // ── userHasPermission — missing branches ──────────────────────────────────

    @Nested
    @DisplayName("userHasPermission() — coverage branches")
    class UserHasPermission {

        @Test
        @DisplayName("assignment does not cover requested location — returns false")
        void userHasPermission_locationNotCovered_returnsFalse() {
            User user = new User();
            user.setId(USER_ID);

            Permission perm = new Permission();
            perm.setName("security:roles:create");

            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(Set.of(perm));

            // LOCATION scope with "loc-A", but we query for "loc-Z" → coversLocation=false
            RoleAssignment assignment = new RoleAssignment();
            assignment.setUser(user);
            assignment.setRole(role);
            assignment.setScopeType(ScopeType.LOCATION);
            assignment.setScopeLocationIds(Set.of("loc-A"));
            assignment.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleAssignmentRepository.findEffectiveAssignmentsByUser(user))
                    .thenReturn(List.of(assignment));

            boolean result = sut.userHasPermission(USER_ID, "security:roles:create", "loc-Z");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("assignment covers location but permission not in role — returns false")
        void userHasPermission_permissionNotInRole_returnsFalse() {
            User user = new User();
            user.setId(USER_ID);

            Permission perm = new Permission();
            perm.setName("security:roles:create");

            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(Set.of(perm));

            // GLOBAL scope covers any location, but permission name doesn't match
            RoleAssignment assignment = new RoleAssignment();
            assignment.setUser(user);
            assignment.setRole(role);
            assignment.setScopeType(ScopeType.GLOBAL);
            assignment.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleAssignmentRepository.findEffectiveAssignmentsByUser(user))
                    .thenReturn(List.of(assignment));

            boolean result = sut.userHasPermission(USER_ID, "security:other:permission", "loc-1");

            assertThat(result).isFalse();
        }
    }

    // ── createRoleAssignment — additional coverage paths ──────────────────────

    @Nested
    @DisplayName("createRoleAssignment() — validation and error paths")
    class CreateRoleAssignment {

        @Test
        @DisplayName("GLOBAL scope with location IDs throws IllegalArgumentException")
        void createRoleAssignment_globalScopeWithLocationIds_throwsIllegalArgument() {
            RoleAssignmentRequest request = new RoleAssignmentRequest(
                    USER_ID, ROLE_ID, ScopeType.GLOBAL,
                    Set.of("loc-1"),
                    LocalDateTime.now(TEST_CLOCK), null);

            assertThatThrownBy(() -> sut.createRoleAssignment(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GLOBAL scope cannot have location IDs");
        }

        @Test
        @DisplayName("user not found throws UserNotFoundException")
        void createRoleAssignment_userNotFound_throwsUserNotFoundException() {
            RoleAssignmentRequest request = new RoleAssignmentRequest(
                    USER_ID, ROLE_ID, ScopeType.GLOBAL, null,
                    LocalDateTime.now(TEST_CLOCK), null);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.createRoleAssignment(request))
                    .isInstanceOf(UserNotFoundException.class);
        }

        @Test
        @DisplayName("role not found throws RoleNotFoundException")
        void createRoleAssignment_roleNotFound_throwsRoleNotFoundException() {
            User user = new User();
            user.setId(USER_ID);
            RoleAssignmentRequest request = new RoleAssignmentRequest(
                    USER_ID, ROLE_ID, ScopeType.GLOBAL, null,
                    LocalDateTime.now(TEST_CLOCK), null);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.createRoleAssignment(request))
                    .isInstanceOf(RoleNotFoundException.class);
        }

        @Test
        @DisplayName("null effectiveStartDate defaults to clock.now()")
        void createRoleAssignment_nullStartDate_defaultsToNow() {
            User user = new User();
            user.setId(USER_ID);
            user.setUsername("tester");

            Role role = new Role();
            role.setId(ROLE_ID);
            role.setName("Tester");
            role.setPermissions(new HashSet<>());

            RoleAssignmentRequest request = new RoleAssignmentRequest(
                    USER_ID, ROLE_ID, ScopeType.GLOBAL, null, null, null); // null start date

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUser_IdAndRole_IdAndScopeType(
                    USER_ID, ROLE_ID, ScopeType.GLOBAL)).thenReturn(List.of());
            when(roleAssignmentRepository.save(any(RoleAssignment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            RoleAssignmentDto result = sut.createRoleAssignment(request);

            assertThat(result.getEffectiveStartDate())
                    .isEqualTo(LocalDateTime.now(TEST_CLOCK));
        }

        @Test
        @DisplayName("overlapping GLOBAL assignment throws IllegalStateException")
        void createRoleAssignment_overlappingGlobal_throwsIllegalState() {
            User user = new User();
            user.setId(USER_ID);

            Role role = new Role();
            role.setId(ROLE_ID);

            RoleAssignment existing = new RoleAssignment();
            existing.setRole(role);
            existing.setScopeType(ScopeType.GLOBAL);
            existing.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));
            // effectiveEndDate = null (open-ended) → overlaps with any new request

            RoleAssignmentRequest request = new RoleAssignmentRequest(
                    USER_ID, ROLE_ID, ScopeType.GLOBAL, null,
                    LocalDateTime.now(TEST_CLOCK), null);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUser_IdAndRole_IdAndScopeType(
                    USER_ID, ROLE_ID, ScopeType.GLOBAL)).thenReturn(List.of(existing));

            assertThatThrownBy(() -> sut.createRoleAssignment(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GLOBAL scope");
        }

        @Test
        @DisplayName("overlapping LOCATION assignment throws IllegalStateException")
        void createRoleAssignment_overlappingLocation_throwsIllegalState() {
            User user = new User();
            user.setId(USER_ID);

            Role role = new Role();
            role.setId(ROLE_ID);

            RoleAssignment existing = new RoleAssignment();
            existing.setRole(role);
            existing.setScopeType(ScopeType.LOCATION);
            existing.setScopeLocationIds(Set.of("loc-1", "loc-2"));
            existing.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));

            RoleAssignmentRequest request = new RoleAssignmentRequest(
                    USER_ID, ROLE_ID, ScopeType.LOCATION,
                    Set.of("loc-1"), // overlaps with existing
                    LocalDateTime.now(TEST_CLOCK), null);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUser_IdAndRole_IdAndScopeType(
                    USER_ID, ROLE_ID, ScopeType.LOCATION)).thenReturn(List.of(existing));

            assertThatThrownBy(() -> sut.createRoleAssignment(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("location");
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
