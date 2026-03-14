package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.service.PermissionRegistryServiceImpl;

@ExtendWith(MockitoExtension.class)
class PermissionRegistryServiceImplTest {

        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Spy
        Clock clock = TEST_CLOCK;

        @Mock
        private PermissionRepository permissionRepository;

        @InjectMocks
        private PermissionRegistryServiceImpl permissionRegistryService;

        @Test
        void registerPermissions_handlesNewUpdatedAndInvalid() {
                Permission existingPermission = new Permission();
                existingPermission.setName("pricing:price_book:view");
                existingPermission.setDescription("Old description");

                PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                                "pricing",
                                "pos-price",
                                List.of(
                                                new PermissionRegistrationRequest.PermissionDefinition(
                                                                "pricing:price_book:view",
                                                                "Updated description"),
                                                new PermissionRegistrationRequest.PermissionDefinition(
                                                                "pricing:price_book:edit",
                                                                "Allows editing"),
                                                new PermissionRegistrationRequest.PermissionDefinition(
                                                                "INVALID_KEY",
                                                                "Invalid")),
                                "1.0");

                when(permissionRepository.findByName("pricing:price_book:view"))
                                .thenReturn(Optional.of(existingPermission));
                when(permissionRepository.findByName("pricing:price_book:edit")).thenReturn(Optional.empty());
                when(permissionRepository.save(any(Permission.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                PermissionRegistrationResponse response = permissionRegistryService.registerPermissions(request);

                assertThat(response.getRegisteredPermissions()).isEqualTo(1);
                assertThat(response.getUpdatedPermissions()).isEqualTo(1);
                assertThat(response.getSkippedPermissions()).isEqualTo(1);
                assertThat(response.getErrors()).isNotEmpty();
        }

        @Test
        void helperMethods_delegateToRepository() {
                Permission permission = new Permission();
                permission.setName("pricing:price_book:view");

                when(permissionRepository.findByDomain("pricing")).thenReturn(List.of(permission));
                when(permissionRepository.findAll()).thenReturn(List.of(permission));
                when(permissionRepository.existsByName("pricing:price_book:view")).thenReturn(true);
                when(permissionRepository.findByName("pricing:price_book:view")).thenReturn(Optional.of(permission));

                assertThat(permissionRegistryService.isValidPermissionName("pricing:price_book:view")).isTrue();
                assertThat(permissionRegistryService.isValidPermissionName("INVALID")).isFalse();
                assertThat(permissionRegistryService.getPermissionsByDomain("pricing")).hasSize(1);
                assertThat(permissionRegistryService.getAllPermissions()).hasSize(1);
                assertThat(permissionRegistryService.permissionExists("pricing:price_book:view")).isTrue();
                assertThat(permissionRegistryService.getPermissionByName("pricing:price_book:view")).isPresent();
        }

        @Test
        void registerPermissions_nullServiceName_throws() {
                PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                                "pricing", null,
                                List.of(new PermissionRegistrationRequest.PermissionDefinition("pricing:p:view",
                                                "desc")),
                                "1.0");

                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> permissionRegistryService.registerPermissions(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("serviceName is required");
        }

        @Test
        void registerPermissions_emptyPermissions_throws() {
                PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                                "pricing", "pos-price", List.of(), "1.0");

                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> permissionRegistryService.registerPermissions(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("permissions must not be empty");
        }

        @Test
        void registerPermissions_descriptionUnchanged_skipsExistingPermission() {
                Permission existingPermission = new Permission();
                existingPermission.setName("pricing:price_book:view");
                existingPermission.setDescription("Same description");

                PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                                "pricing", "pos-price",
                                List.of(new PermissionRegistrationRequest.PermissionDefinition(
                                                "pricing:price_book:view", "Same description")),
                                "1.0");

                when(permissionRepository.findByName("pricing:price_book:view"))
                                .thenReturn(Optional.of(existingPermission));

                PermissionRegistrationResponse response = permissionRegistryService.registerPermissions(request);

                assertThat(response.getUpdatedPermissions()).isZero();
                assertThat(response.getSkippedPermissions()).isEqualTo(1);
        }

        @Test
        void isValidPermissionName_emptyString_returnsFalse() {
                assertThat(permissionRegistryService.isValidPermissionName("")).isFalse();
        }

        @Test
        void isValidPermissionName_null_returnsFalse() {
                assertThat(permissionRegistryService.isValidPermissionName(null)).isFalse();
        }

        // =========================================================
        // registerNewPermission: second findByName backfill paths
        // =========================================================

        @Test
        void registerNewPermission_backfillsBitIndex_whenSecondFindReturnsExistingWithNullBitIndex() {
                // "accounting:je:view" maps to PermissionCode.ACCOUNTING__JE__VIEW (bitIndex 0)
                Permission existing = new Permission();
                existing.setName("accounting:je:view");
                existing.setDescription("desc");
                // bitIndex is null → should be backfilled

                PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                                "accounting", "pos-accounting",
                                List.of(new PermissionRegistrationRequest.PermissionDefinition(
                                                "accounting:je:view", "desc")),
                                "1.0");

                // First call (processPermissionDefinition) → empty; second call (registerNewPermission) → existing
                when(permissionRepository.findByName("accounting:je:view"))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(existing));
                when(permissionRepository.save(any(Permission.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                PermissionRegistrationResponse response = permissionRegistryService.registerPermissions(request);

                assertThat(response.getUpdatedPermissions()).isEqualTo(1);
                assertThat(response.getRegisteredPermissions()).isZero();
        }

        @Test
        void registerNewPermission_skipsExisting_whenSecondFindReturnsPresentWithBitIndexAlreadySet() {
                Permission existing = new Permission();
                existing.setName("accounting:je:view");
                existing.setDescription("desc");
                existing.setBitIndex(0); // already backfilled

                PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                                "accounting", "pos-accounting",
                                List.of(new PermissionRegistrationRequest.PermissionDefinition(
                                                "accounting:je:view", "desc")),
                                "1.0");

                when(permissionRepository.findByName("accounting:je:view"))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(existing));

                PermissionRegistrationResponse response = permissionRegistryService.registerPermissions(request);

                assertThat(response.getSkippedPermissions()).isEqualTo(1);
                assertThat(response.getUpdatedPermissions()).isZero();
        }

        @Test
        void registerNewPermission_skipsExisting_whenSecondFindReturnsPresentWithNullBitIndexAndUnknownCode() {
                // "pricing:unknown:perm" passes isValidPermissionName but is NOT in PermissionCode → changed stays false
                Permission existing = new Permission();
                existing.setName("pricing:unknown:perm");
                existing.setDescription("desc");
                // bitIndex is null and no PermissionCode match → changed=false → skip

                PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                                "pricing", "pos-price",
                                List.of(new PermissionRegistrationRequest.PermissionDefinition(
                                                "pricing:unknown:perm", "desc")),
                                "1.0");

                when(permissionRepository.findByName("pricing:unknown:perm"))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.of(existing));

                PermissionRegistrationResponse response = permissionRegistryService.registerPermissions(request);

                assertThat(response.getSkippedPermissions()).isEqualTo(1);
                assertThat(response.getUpdatedPermissions()).isZero();
        }

        @Test
        void registerNewPermission_saveThrows_addsErrorAndSkips() {
                // save() throws IllegalArgumentException → caught in registerNewPermission's inner try/catch
                PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                                "accounting", "pos-accounting",
                                List.of(new PermissionRegistrationRequest.PermissionDefinition(
                                                "accounting:je:view", "desc")),
                                "1.0");

                when(permissionRepository.findByName("accounting:je:view"))
                                .thenReturn(Optional.empty())
                                .thenReturn(Optional.empty());
                when(permissionRepository.save(any(Permission.class)))
                                .thenThrow(new IllegalArgumentException("forced error during save"));

                PermissionRegistrationResponse response = permissionRegistryService.registerPermissions(request);

                assertThat(response.getErrors()).isNotEmpty();
                assertThat(response.getSkippedPermissions()).isEqualTo(1);
                assertThat(response.getRegisteredPermissions()).isZero();
        }
}