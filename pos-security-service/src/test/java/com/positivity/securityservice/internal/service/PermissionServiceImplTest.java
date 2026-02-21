package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionServiceImpl")
class PermissionServiceImplTest {

    @Mock
    private PermissionRepository permissionRepository;

    @InjectMocks
    private PermissionServiceImpl sut;

    @Nested
    @DisplayName("registerPermissions()")
    class RegisterPermissions {
        @Test
        @DisplayName("registers valid permission key")
        void registerPermissions_validKey_registersPermission() {
            PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                    "pricing",
                    "pos-price",
                    List.of(new PermissionRegistrationRequest.PermissionDefinition(
                            "pricing:price_book:edit",
                            "Allows editing price books")),
                    "1.0");

            when(permissionRepository.findByName("pricing:price_book:edit")).thenReturn(Optional.empty());
            when(permissionRepository.save(any(Permission.class))).thenAnswer(invocation -> {
                Permission permission = invocation.getArgument(0);
                permission.setId(UUID.randomUUID());
                return permission;
            });

            var result = sut.registerPermissions(request);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo("pricing:price_book:edit");
            verify(permissionRepository).save(any(Permission.class));
        }

        @Test
        @DisplayName("updates existing permission when key already exists")
        void registerPermissions_existingPermission_updates() {
            Permission existingPermission = new Permission();
            existingPermission.setId(UUID.randomUUID());
            existingPermission.setName("pricing:price_book:edit");
            existingPermission.setDescription("Old description");

            PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                    "pricing",
                    "pos-price",
                    List.of(new PermissionRegistrationRequest.PermissionDefinition(
                            "pricing:price_book:edit",
                            "Updated description")),
                    "1.0");

            when(permissionRepository.findByName("pricing:price_book:edit"))
                    .thenReturn(Optional.of(existingPermission));
            when(permissionRepository.save(any(Permission.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var result = sut.registerPermissions(request);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDescription()).isEqualTo("Updated description");
        }

        @Test
        @DisplayName("rejects invalid permission key format")
        void registerPermissions_invalidKey_throws() {
            PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                    "pricing",
                    "pos-price",
                    List.of(new PermissionRegistrationRequest.PermissionDefinition(
                            "invalid_key",
                            "Invalid format")),
                    "1.0");

            assertThatThrownBy(() -> sut.registerPermissions(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Permission key must match domain:resource:action");
        }

        @Test
        @DisplayName("returns empty when permissions list is null")
        void registerPermissions_nullPermissions_returnsEmpty() {
            PermissionRegistrationRequest request = new PermissionRegistrationRequest();
            request.setServiceName("pos-price");
            request.setPermissions(null);

            var result = sut.registerPermissions(request);

            assertThat(result).isEmpty();
            verify(permissionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getPermission()")
    class GetPermission {
        @Test
        @DisplayName("returns mapped dto when permission exists")
        void getPermission_found_returnsDto() {
            UUID permissionId = UUID.randomUUID();
            Permission permission = new Permission();
            permission.setId(permissionId);
            permission.setName("pricing:price_book:edit");
            permission.setDescription("Allows editing price books");
            permission.parsePermissionName();

            when(permissionRepository.findById(permissionId)).thenReturn(Optional.of(permission));

            var result = sut.getPermission(permissionId);

            assertThat(result.getId()).isEqualTo("pricing:price_book:edit");
            assertThat(result.getDomain()).isEqualTo("pricing");
        }

        @Test
        @DisplayName("throws when permission does not exist")
        void getPermission_notFound_throws() {
            UUID permissionId = UUID.randomUUID();
            when(permissionRepository.findById(permissionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.getPermission(permissionId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Permission not found");
        }
    }

    @Nested
    @DisplayName("getByDomain()")
    class GetByDomain {
        @Test
        @DisplayName("returns mapped permissions for domain")
        void getByDomain_found_returnsDtos() {
            Permission permission = new Permission();
            permission.setName("pricing:price_book:view");
            permission.setDescription("Allows viewing price books");
            permission.setDeprecated(false);
            permission.parsePermissionName();

            when(permissionRepository.findByDomain("pricing")).thenReturn(new ArrayList<>(List.of(permission)));

            var result = sut.getByDomain("pricing");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDomain()).isEqualTo("pricing");
        }
    }
}
