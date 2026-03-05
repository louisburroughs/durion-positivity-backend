package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.PrincipalRoleRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("RolePermissionServiceImpl")
class RolePermissionServiceImplTest {

    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private PrincipalRoleRepository principalRoleRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private RolePermissionServiceImpl sut;

    @AfterEach
    void cleanupSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("createRole()")
    class CreateRole {
        @Test
        @DisplayName("creates role with current authenticated actor")
        void createRole_success_withAuthenticatedActor() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "agent-user",
                            "n/a",
                            java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            when(roleRepository.existsByName("MANAGER")).thenReturn(false);
            when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

            RoleDto result = sut.createRole("MANAGER", "Manager role");

            assertThat(result.getName()).isEqualTo("MANAGER");
            assertThat(result.getCreatedBy()).isEqualTo("agent-user");
        }

        @Test
        @DisplayName("creates role with system actor when unauthenticated")
        void createRole_success_withSystemActor() {
            when(roleRepository.existsByName("MANAGER")).thenReturn(false);
            when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

            RoleDto result = sut.createRole("MANAGER", "Manager role");

            assertThat(result.getCreatedBy()).isEqualTo("system");
        }

        @Test
        @DisplayName("throws when role already exists")
        void createRole_duplicate_throws() {
            when(roleRepository.existsByName("MANAGER")).thenReturn(true);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.createRole("MANAGER", "Manager role"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Role already exists");
        }
    }

    @Nested
    @DisplayName("grantPermission()")
    class GrantPermission {
        @Test
        @DisplayName("grants permission to role and publishes audit event")
        void grantPermission_success() {
            UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            String permissionKey = "security:role:grant";

            Role role = new Role();
            role.setId(roleId);
            role.setPermissions(new java.util.HashSet<>());

            Permission permission = new Permission();
            permission.setName(permissionKey);

            when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
            when(permissionRepository.findByName(permissionKey)).thenReturn(Optional.of(permission));
            when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

            RoleDto result = sut.grantPermission(roleId, permissionKey);

            assertThat(result.getPermissions()).extracting("name").contains(permissionKey);
            verify(applicationEventPublisher).publishEvent(any());
        }

        @Test
        @DisplayName("auto-registers permission when key does not exist")
        void grantPermission_missingPermission_registersAndGrants() {
            UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            String permissionKey = "security:role:grant";

            Role role = new Role();
            role.setId(roleId);
            role.setPermissions(new java.util.HashSet<>());

            when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
            when(permissionRepository.findByName(permissionKey)).thenReturn(Optional.empty());
            when(permissionRepository.save(any(Permission.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

            RoleDto result = sut.grantPermission(roleId, permissionKey);

            assertThat(result.getPermissions()).hasSize(1);
            assertThat(result.getPermissions().iterator().next().getName()).isEqualTo(permissionKey);
            verify(permissionRepository).save(any(Permission.class));
        }

        @Test
        @DisplayName("throws when role does not exist")
        void grantPermission_roleNotFound_throws() {
            UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.grantPermission(roleId, "security:role:grant"))
                    .isInstanceOf(RoleNotFoundException.class)
                    .hasMessageContaining("Role not found");
        }
    }

    @Nested
    @DisplayName("revokePermission()")
    class RevokePermission {
        @Test
        @DisplayName("removes permission from role")
        void revokePermission_success() {
            UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            String permissionKey = "security:role:grant";

            Permission permission = new Permission();
            permission.setName(permissionKey);

            Role role = new Role();
            role.setId(roleId);
            role.setPermissions(new java.util.HashSet<>(Set.of(permission)));

            when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
            when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

            RoleDto result = sut.revokePermission(roleId, permissionKey);

            assertThat(result.getPermissions()).isEmpty();
        }

        @Test
        @DisplayName("throws when role does not exist")
        void revokePermission_roleNotFound_throws() {
            UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> sut.revokePermission(roleId, "security:role:grant"))
                    .isInstanceOf(RoleNotFoundException.class)
                    .hasMessageContaining("Role not found");
        }
    }

    @Nested
    @DisplayName("assignRoleToPrincipal()")
    class AssignRoleToPrincipal {
        @Test
        @DisplayName("saves principal role when assignment does not exist")
        void assignRoleToPrincipal_createsAssignment() {
            UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            Role role = new Role();
            role.setId(roleId);

            when(principalRoleRepository.existsByPrincipalIdAndRole_Id("principal-1", roleId)).thenReturn(false);
            when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

            sut.assignRoleToPrincipal("principal-1", roleId);

            verify(principalRoleRepository).save(any());
        }

        @Test
        @DisplayName("does nothing when assignment already exists")
        void assignRoleToPrincipal_existingAssignment_noop() {
            UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            when(principalRoleRepository.existsByPrincipalIdAndRole_Id("principal-1", roleId)).thenReturn(true);

            sut.assignRoleToPrincipal("principal-1", roleId);

            verify(roleRepository, never()).findById(any());
            verify(principalRoleRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws when role does not exist")
        void assignRoleToPrincipal_roleNotFound_throws() {
            UUID roleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            when(principalRoleRepository.existsByPrincipalIdAndRole_Id("principal-1", roleId)).thenReturn(false);
            when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.assignRoleToPrincipal("principal-1", roleId))
                    .isInstanceOf(RoleNotFoundException.class)
                    .hasMessageContaining("Role not found");
        }
    }
}
