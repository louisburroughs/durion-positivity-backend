package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleCreateRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.exception.RoleAssignmentNotFoundException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.EffectiveGrantResolver.EffectiveGrants;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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

    @Mock
    private RolePersonaEventEmitter rolePersonaEventEmitter;

    @Mock
    private EffectiveGrantResolver effectiveGrantResolver;

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
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            "admin-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

            Role saved = new Role();
            saved.setId(ROLE_ID);
            saved.setName("ShopManager");
            saved.setDescription("Manages the shop floor");
            saved.setCreatedBy("admin-user");
            saved.setPermissions(new HashSet<>());
            when(roleRepository.existsByNameIgnoreCase("ShopManager")).thenReturn(false);
            when(roleRepository.save(any(Role.class))).thenReturn(saved);

            RoleDto result = sut.createRole(
                    new RoleCreateRequest("ShopManager", "Manages the shop floor", null, null, null, null, null));

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

            assertThatThrownBy(() ->
                            sut.createRole(new RoleCreateRequest("shopmanager", null, null, null, null, null, null)))
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
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            "rbac-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

            Role role = new Role();
            role.setId(ROLE_ID);
            role.setPermissions(new HashSet<>());
            UUID permissionId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
            Permission perm = new Permission();
            perm.setId(permissionId);
            perm.setName(permissionKey);
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(permissionRepository.findByName(permissionKey)).thenReturn(Optional.of(perm));
            when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

            sut.assignPermissionToRole(ROLE_ID, permissionKey);

            assertThat(role.getPermissions()).extracting(Permission::getName).contains(permissionKey);
            assertThat(role.getLastModifiedBy()).isEqualTo("rbac-admin");
            assertThat(role.getLastModifiedAt()).isEqualTo(Instant.now(TEST_CLOCK));
            verify(roleRepository).save(role);
            // #1512: the grant row records the actor, not only the role's lastModifiedBy.
            verify(roleRepository)
                    .recordGrantProvenance(eq(ROLE_ID), eq(List.of(permissionId)), eq("rbac-admin"), any());
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
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "rbac-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        Role role = new Role();
        role.setId(ROLE_ID);
        Permission perm = new Permission();
        perm.setName(permissionKey);
        role.setPermissions(new HashSet<>(Set.of(perm)));
        when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));

        sut.revokePermissionFromRole(ROLE_ID, permissionKey);

        assertThat(role.getPermissions()).extracting(Permission::getName).doesNotContain(permissionKey);
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
            assignment.setEffectiveStartDate(
                    java.time.LocalDateTime.now(TEST_CLOCK).minusDays(1));
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

            assertThatThrownBy(() -> sut.updateRolePermissions(request)).isInstanceOf(RoleNotFoundException.class);
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

        assertThatThrownBy(() -> sut.deleteRole(unknownId)).isInstanceOf(RoleNotFoundException.class);
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

        assertThatThrownBy(() -> sut.getRoleByName("Unknown")).isInstanceOf(RoleNotFoundException.class);
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
        past.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(10));
        // effectiveEndDate already set (in the past)

        RoleAssignment current = new RoleAssignment();
        current.setUser(user);
        current.setRole(role);
        current.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(roleAssignmentRepository.findAllByUser_Id(USER_ID)).thenReturn(List.of(past, current));

        List<RoleAssignmentDto> result = sut.getAssignmentsForUser(USER_ID, true);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("getAssignmentsForUser() returns the role's stable code alongside its id")
    void getAssignmentsForUser_carriesTheRoleCode() {
        // Without roleCode a caller can only turn a listing into something renderable — or
        // revocable, which addresses an assignment by code — by resolving every roleId through
        // the role catalog (issue #1886).
        User user = new User();
        user.setId(USER_ID);

        Role role = new Role();
        role.setId(ROLE_ID);
        role.setName("SHOP_MGR");
        role.setPermissions(new HashSet<>());

        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(roleAssignmentRepository.findCurrentAssignmentsByUser(eq(user), any()))
                .thenReturn(List.of(assignment));

        assertThat(sut.getAssignmentsForUser(USER_ID, false)).singleElement().satisfies(dto -> {
            assertThat(dto.getRoleId()).isEqualTo(ROLE_ID);
            // The name IS the stable code in this system: it is what GET /v1/roles/by-name/{name}
            // resolves and what RoleDto.name carries.
            assertThat(dto.getRoleCode()).isEqualTo("SHOP_MGR");
        });
    }

    // ── userHasPermission — effective dating without a scope branch (#1875) ───

    @Nested
    @DisplayName("userHasPermission() — effective dating is the only filter")
    class UserHasPermission {

        @Test
        @DisplayName("permission held through the resolver's effective grants — returns true")
        void userHasPermission_effectiveAssignmentGrants_returnsTrue() {
            User user = new User();
            user.setId(USER_ID);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(effectiveGrantResolver.resolve(user))
                    .thenReturn(new EffectiveGrants(Set.of(), Set.of(), Set.of("security:roles:create")));

            assertThat(sut.userHasPermission(USER_ID, "security:roles:create")).isTrue();
        }

        @Test
        @DisplayName("delegates entirely to the resolver — history is never read directly")
        void userHasPermission_consultsResolverOnly() {
            // The dating guarantee (an expired assignment is absent from the effective set) is
            // proven against the real query by EffectiveGrantResolverImplTest and
            // UserHasPermissionEffectiveDatingIT; here it is enough that this method asks the
            // resolver and never reaches into role_assignments history itself (#1914).
            User user = new User();
            user.setId(USER_ID);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(effectiveGrantResolver.resolve(user)).thenReturn(new EffectiveGrants(Set.of(), Set.of(), Set.of()));

            assertThat(sut.userHasPermission(USER_ID, "security:roles:create")).isFalse();
            verify(effectiveGrantResolver).resolve(user);
            verify(roleAssignmentRepository, never()).findAllByUser_Id(any());
            verify(roleAssignmentRepository, never()).findEffectiveAssignmentsByUser(any(), any());
        }

        @Test
        @DisplayName("resolver's effective grants lack the permission — returns false")
        void userHasPermission_permissionNotInRole_returnsFalse() {
            User user = new User();
            user.setId(USER_ID);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(effectiveGrantResolver.resolve(user))
                    .thenReturn(new EffectiveGrants(Set.of(), Set.of(), Set.of("security:roles:create")));

            assertThat(sut.userHasPermission(USER_ID, "security:other:permission"))
                    .isFalse();
        }
    }

    // ── createRoleAssignment — additional coverage paths ──────────────────────

    @Nested
    @DisplayName("createRoleAssignment() — validation and error paths")
    class CreateRoleAssignment {

        @Test
        @DisplayName("user not found throws UserNotFoundException")
        void createRoleAssignment_userNotFound_throwsUserNotFoundException() {
            RoleAssignmentRequest request =
                    new RoleAssignmentRequest(USER_ID, ROLE_ID, LocalDateTime.now(TEST_CLOCK), null);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.createRoleAssignment(request)).isInstanceOf(UserNotFoundException.class);
        }

        @Test
        @DisplayName("role not found throws RoleNotFoundException")
        void createRoleAssignment_roleNotFound_throwsRoleNotFoundException() {
            User user = new User();
            user.setId(USER_ID);
            RoleAssignmentRequest request =
                    new RoleAssignmentRequest(USER_ID, ROLE_ID, LocalDateTime.now(TEST_CLOCK), null);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.createRoleAssignment(request)).isInstanceOf(RoleNotFoundException.class);
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

            RoleAssignmentRequest request = new RoleAssignmentRequest(USER_ID, ROLE_ID, null, null); // null start date

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUser_IdAndRole_Id(USER_ID, ROLE_ID))
                    .thenReturn(List.of());
            when(roleAssignmentRepository.save(any(RoleAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

            RoleAssignmentDto result = sut.createRoleAssignment(request);

            assertThat(result.getEffectiveStartDate()).isEqualTo(LocalDateTime.now(TEST_CLOCK));
        }

        @Test
        @DisplayName("a bounded assignment is created unrevoked")
        void createRoleAssignment_boundedWindow_leavesRevokedAtNull() {
            // setEffectiveEndDate used to stamp revokedAt, and this is the call that goes through
            // it, so every bounded assignment was born marked revoked (#1910).
            User user = new User();
            user.setId(USER_ID);
            user.setUsername("tester");

            Role role = new Role();
            role.setId(ROLE_ID);
            role.setName("Tester");
            role.setPermissions(new HashSet<>());

            RoleAssignmentRequest request = new RoleAssignmentRequest(
                    USER_ID,
                    ROLE_ID,
                    LocalDateTime.now(TEST_CLOCK),
                    LocalDateTime.now(TEST_CLOCK).plusYears(1));

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUser_IdAndRole_Id(USER_ID, ROLE_ID))
                    .thenReturn(List.of());
            when(roleAssignmentRepository.save(any(RoleAssignment.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            assertThat(sut.createRoleAssignment(request).getRevokedAt()).isNull();
        }

        @Test
        @DisplayName("a window starting exactly where another ends is a handover, not an overlap")
        void createRoleAssignment_windowStartingAtPriorEnd_isAccepted() {
            // The window is half-open, so touching windows do not overlap. The end-inclusive
            // comparison rejected a clean handover as a conflict.
            User user = new User();
            user.setId(USER_ID);
            user.setUsername("tester");

            Role role = new Role();
            role.setId(ROLE_ID);
            role.setName("Tester");
            role.setPermissions(new HashSet<>());

            LocalDateTime handover = LocalDateTime.now(TEST_CLOCK);

            RoleAssignment ending = new RoleAssignment();
            ending.setRole(role);
            ending.setEffectiveStartDate(handover.minusDays(30));
            ending.setEffectiveEndDate(handover);

            RoleAssignmentRequest request = new RoleAssignmentRequest(USER_ID, ROLE_ID, handover, null);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUser_IdAndRole_Id(USER_ID, ROLE_ID))
                    .thenReturn(List.of(ending));
            when(roleAssignmentRepository.save(any(RoleAssignment.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            assertThat(sut.createRoleAssignment(request).getEffectiveStartDate())
                    .isEqualTo(handover);
        }

        @Test
        @DisplayName("overlapping assignment for the same user and role throws IllegalStateException")
        void createRoleAssignment_overlappingWindow_throwsIllegalState() {
            User user = new User();
            user.setId(USER_ID);

            Role role = new Role();
            role.setId(ROLE_ID);

            RoleAssignment existing = new RoleAssignment();
            existing.setRole(role);
            existing.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));
            // effectiveEndDate = null (open-ended) → overlaps with any new request

            RoleAssignmentRequest request =
                    new RoleAssignmentRequest(USER_ID, ROLE_ID, LocalDateTime.now(TEST_CLOCK), null);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUser_IdAndRole_Id(USER_ID, ROLE_ID))
                    .thenReturn(List.of(existing));

            assertThatThrownBy(() -> sut.createRoleAssignment(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Overlapping role assignment");
        }

        @Test
        @DisplayName("non-overlapping windows for the same user and role are accepted")
        void createRoleAssignment_disjointWindow_isAccepted() {
            User user = new User();
            user.setId(USER_ID);
            user.setUsername("tester");

            Role role = new Role();
            role.setId(ROLE_ID);
            role.setName("Tester");
            role.setPermissions(new HashSet<>());

            RoleAssignment ended = new RoleAssignment();
            ended.setRole(role);
            ended.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(30));
            ended.setEffectiveEndDate(LocalDateTime.now(TEST_CLOCK).minusDays(10));

            RoleAssignmentRequest request =
                    new RoleAssignmentRequest(USER_ID, ROLE_ID, LocalDateTime.now(TEST_CLOCK), null);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(roleRepository.findById(ROLE_ID)).thenReturn(Optional.of(role));
            when(roleAssignmentRepository.findByUser_IdAndRole_Id(USER_ID, ROLE_ID))
                    .thenReturn(List.of(ended));
            when(roleAssignmentRepository.save(any(RoleAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

            RoleAssignmentDto result = sut.createRoleAssignment(request);

            assertThat(result.getEffectiveStartDate()).isEqualTo(LocalDateTime.now(TEST_CLOCK));
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

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(effectiveGrantResolver.resolve(user))
                .thenReturn(new EffectiveGrants(
                        Set.of(role1, role2), Set.of(), Set.of("security:roles:create", "security:users:view")));

        Set<PermissionDto> permissions = sut.getUserPermissions(USER_ID);

        assertThat(permissions)
                .extracting(PermissionDto::getName)
                .containsExactlyInAnyOrder("security:roles:create", "security:users:view");
    }
}
