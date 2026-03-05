package com.positivity.securityservice.service;

import java.time.ZoneOffset;
import java.time.Instant;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.ScopeType;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.RoleManagementServiceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class RoleManagementServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


        @Mock
        private RoleRepository roleRepository;
        @Mock
        private PermissionRepository permissionRepository;
        @Mock
        private RoleAssignmentRepository roleAssignmentRepository;
        @Mock
        private UserRepository userRepository;

        @InjectMocks
        private RoleManagementServiceImpl roleManagementService;

        @AfterEach
        void cleanupSecurityContext() {
                SecurityContextHolder.clearContext();
        }

        @Test
        void createRole_andUpdatePermissions_success() {
                SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                                "agent-user",
                                                "n/a",
                                                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

                Role role = new Role();
                UUID roleId = UUID.randomUUID();
                role.setId(roleId);
                role.setName("MANAGER");

                Permission permission = new Permission();
                permission.setName("security:role:grant");

                when(roleRepository.existsByName("MANAGER")).thenReturn(false);
                when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
                when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
                when(permissionRepository.findByName("security:role:grant")).thenReturn(Optional.of(permission));

                RoleDto created = roleManagementService.createRole("MANAGER", "Manager role");
                RoleDto updated = roleManagementService.updateRolePermissions(
                                new RolePermissionsRequest(roleId, Set.of("security:role:grant")));

                assertThat(created.getCreatedBy()).isEqualTo("agent-user");
                assertThat(updated.getPermissions()).extracting("name").contains("security:role:grant");
        }

        @Test
        void createRoleAssignment_andPermissionChecks_success() {
                UUID userId = UUID.randomUUID();
                UUID roleId = UUID.randomUUID();
                UUID assignmentId = UUID.randomUUID();

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
                assignment.setScopeType(ScopeType.GLOBAL);
                assignment.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));

                RoleAssignmentRequest request = new RoleAssignmentRequest(
                                userId,
                                roleId,
                                ScopeType.GLOBAL,
                                Set.of(),
                                LocalDateTime.now(TEST_CLOCK).minusHours(1),
                                null);

                when(userRepository.findById(userId)).thenReturn(Optional.of(user));
                when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
                when(roleAssignmentRepository.findByUser_IdAndRole_IdAndScopeType(userId, roleId, ScopeType.GLOBAL))
                                .thenReturn(List.of());
                when(roleAssignmentRepository.save(any(RoleAssignment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(roleAssignmentRepository.findEffectiveAssignmentsByUser(user)).thenReturn(List.of(assignment));
                when(roleAssignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));

                var createdAssignment = roleManagementService.createRoleAssignment(request);
                boolean hasPermission = roleManagementService.userHasPermission(userId, "security:role:grant", "loc-1");
                roleManagementService.revokeRoleAssignment(assignmentId, LocalDateTime.now(TEST_CLOCK).plusDays(1));

                assertThat(createdAssignment.getRoleId()).isEqualTo(roleId);
                assertThat(hasPermission).isTrue();
        }

        @Test
        void validationBranches_throwOnInvalidScopeAndMissingRole() {
                UUID userId = UUID.randomUUID();
                UUID roleId = UUID.randomUUID();

                RoleAssignmentRequest invalidLocationRequest = new RoleAssignmentRequest(
                                userId,
                                roleId,
                                ScopeType.LOCATION,
                                Set.of(),
                                LocalDateTime.now(TEST_CLOCK),
                                null);

                assertThatThrownBy(() -> roleManagementService.createRoleAssignment(invalidLocationRequest))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("LOCATION scope requires at least one location ID");

                when(roleRepository.findByName("MISSING")).thenReturn(Optional.empty());
                assertThatThrownBy(() -> roleManagementService.getRoleByName("MISSING"))
                                .isInstanceOf(RuntimeException.class)
                                .hasMessageContaining("Role not found");
        }
}
