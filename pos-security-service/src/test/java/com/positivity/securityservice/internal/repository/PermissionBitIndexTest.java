package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.config.PermissionBitIndexValidator;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.service.PermissionRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration and unit tests for PERM-002: Permission.bitIndex JPA field
 * mapping
 * and PermissionBitIndexValidator startup validation behavior.
 *
 * <p>
 * JPA tests (1–4) use {@code @SpringBootTest} + H2 ({@code test} profile) to
 * exercise the entity mapping and repository query methods. Each test runs
 * inside
 * a rolled-back transaction for isolation. Validator tests (5–7) use Mockito to
 * unit-test the startup component in isolation.
 *
 * Issue: PERM-002
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@DisplayName("PermissionBitIndexTest — PERM-002")
class PermissionBitIndexTest {

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private PermissionRegistryService permissionRegistryService;

    // ──────────────────────────────────────────────────────────────────────────
    // JPA mapping tests (tests 1 & 2 — expected GREEN with current scaffold)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bitIndex field round-trips through JPA persistence")
    void permissionBitIndexFieldRoundTrip() {
        Permission p = buildPermission("accounting:je:view", 42);
        Permission saved = permissionRepository.save(p);

        Permission reloaded = permissionRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getBitIndex()).isEqualTo(42);
    }

    @Test
    @DisplayName("null bitIndex is allowed (nullable column mapping)")
    void permissionWithNullBitIndexIsAllowed() {
        Permission p = buildPermission("accounting:je:create", null);
        Permission saved = permissionRepository.save(p);

        Permission reloaded = permissionRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getBitIndex()).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Repository query tests (tests 3 & 4)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findByBitIndex returns permission for a saved index")
    void findByBitIndexReturnsPermissionForKnownIndex() {
        Permission p = buildPermission("pricing:price_book:edit", 7);
        permissionRepository.save(p);

        Optional<Permission> result = permissionRepository.findByBitIndex(7);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("pricing:price_book:edit");
    }

    @Test
    @DisplayName("findByBitIndex returns empty for an unassigned index")
    void findByBitIndexReturnsEmptyForUnassignedIndex() {
        Optional<Permission> result = permissionRepository.findByBitIndex(999);

        assertThat(result).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PermissionBitIndexValidator tests (tests 5 & 6)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Unit tests for {@code PermissionBitIndexValidator} startup component.
     * Isolated from JPA slice — uses Mockito for repository and
     * OutputCaptureExtension to assert log output.
     *
     * Issue: PERM-002
     */
    @Nested
    @ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
    @DisplayName("PermissionBitIndexValidator")
    class ValidatorTests {

        @Mock
        private PermissionRepository mockPermissionRepository;

        @Test
        @DisplayName("logs WARN when catalog permission has null bitIndex")
        void permissionBitIndexValidatorLogsWarnForUnmappedPermission(CapturedOutput output) {
            Permission unmapped = buildPermission("accounting:je:view", null);
            when(mockPermissionRepository.findAll()).thenReturn(List.of(unmapped));

            PermissionBitIndexValidator validator = new PermissionBitIndexValidator(mockPermissionRepository);
            validator.run(mock(ApplicationArguments.class));

            assertThat(output.toString())
                    .contains("WARN")
                    .contains("accounting:je:view");
        }

        @Test
        @DisplayName("does not log WARN when all permissions have a bitIndex")
        void permissionBitIndexValidatorDoesNotWarnWhenAllPermissionsAreMapped(CapturedOutput output) {
            Permission mapped = buildPermission("accounting:je:view", 0);
            when(mockPermissionRepository.findAll()).thenReturn(List.of(mapped));

            PermissionBitIndexValidator validator = new PermissionBitIndexValidator(mockPermissionRepository);
            validator.run(mock(ApplicationArguments.class));

            assertThat(output.toString()).doesNotContain("WARN");
        }

        @Test
        @DisplayName("logs WARN for permission not in PermissionCode catalog")
        void permissionBitIndexValidatorLogsWarnForUnknownPermission(CapturedOutput output) {
            Permission unknown = buildPermission("custom:unknown:action", null);
            when(mockPermissionRepository.findAll()).thenReturn(List.of(unknown));
            new PermissionBitIndexValidator(mockPermissionRepository)
                    .run(mock(ApplicationArguments.class));
            assertThat(output.toString())
                    .contains("WARN")
                    .contains("custom:unknown:action")
                    .contains("not in the PermissionCode catalog");
        }

        @Test
        @DisplayName("logs WARN when catalog permission has a mismatched bitIndex")
        void permissionBitIndexValidatorLogsWarnForMismatchedBitIndex(CapturedOutput output) {
            // ACCOUNTING__JE__VIEW has bitIndex=0 in the catalog; supply 999 to trigger the
            // else-if mismatch branch in PermissionBitIndexValidator.run()
            Permission mismatch = buildPermission("accounting:je:view", 999);
            when(mockPermissionRepository.findAll()).thenReturn(List.of(mismatch));

            PermissionBitIndexValidator validator = new PermissionBitIndexValidator(mockPermissionRepository);
            validator.run(mock(ApplicationArguments.class));

            assertThat(output.toString())
                    .contains("WARN")
                    .contains("accounting:je:view")
                    .contains("catalog expects");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Registration bit-index tests (acceptance criterion: seeded perms get indexes)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link PermissionRegistryService#registerPermissions} correctly
     * assigns {@code bitIndex} values from the {@code PermissionCode} catalog for
     * known permission names and leaves {@code bitIndex} null for unknowns.
     *
     * Issue: PERM-002
     */
    @Nested
    @DisplayName("Permission registration assigns bitIndex from catalog")
    class RegistrationBitIndexTests {

        @Test
        @DisplayName("known permissions get correct bitIndex on registration")
        void registeredPermissionGetsCorrectBitIndex() {
            PermissionRegistrationRequest.PermissionDefinition def1 = new PermissionRegistrationRequest.PermissionDefinition(
                    "accounting:je:view", "View journal entries");
            PermissionRegistrationRequest.PermissionDefinition def2 = new PermissionRegistrationRequest.PermissionDefinition(
                    "accounting:je:create", "Create journal entries");

            PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                    "accounting", "pos-accounting",
                    List.of(def1, def2), "1.0");
            permissionRegistryService.registerPermissions(request);

            Optional<Permission> viewPerm = permissionRepository.findByName("accounting:je:view");
            Optional<Permission> createPerm = permissionRepository.findByName("accounting:je:create");

            assertThat(viewPerm).isPresent();
            assertThat(viewPerm.get().getBitIndex()).isZero();
            assertThat(createPerm).isPresent();
            assertThat(createPerm.get().getBitIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("all 215 catalog permissions get correct bitIndex when registered via service")
        void allCatalogPermissionsGetCorrectBitIndexWhenRegistered() {
            List<PermissionRegistrationRequest.PermissionDefinition> defs = Arrays.stream(PermissionCode.values())
                    .map(pc -> new PermissionRegistrationRequest.PermissionDefinition(pc.code(),
                            "Catalog: " + pc.name()))
                    .toList();

            PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                    "catalog", "pos-test", defs, "1.0");
            PermissionRegistrationResponse response = permissionRegistryService.registerPermissions(request);

            assertThat(response.getRegisteredPermissions())
                    .as("All 215 catalog permissions should be registered (0 failures)")
                    .isEqualTo(215);

            for (PermissionCode pc : PermissionCode.values()) {
                Optional<Permission> perm = permissionRepository.findByName(pc.code());
                assertThat(perm)
                        .as("Permission '%s' should be persisted", pc.code())
                        .isPresent();
                assertThat(perm.get().getBitIndex())
                        .as("Permission '%s' should have bitIndex=%d", pc.code(), pc.bitIndex())
                        .isEqualTo(pc.bitIndex());
            }
        }

        @Test
        @DisplayName("unknown permission name gets null bitIndex on registration")
        void registeredUnknownPermissionHasNullBitIndex() {
            PermissionRegistrationRequest.PermissionDefinition def = new PermissionRegistrationRequest.PermissionDefinition(
                    "custom:resource:action", "Unknown permission");

            PermissionRegistrationRequest request = new PermissionRegistrationRequest(
                    "custom", "pos-custom",
                    List.of(def), "1.0");
            permissionRegistryService.registerPermissions(request);

            Optional<Permission> perm = permissionRepository.findByName("custom:resource:action");
            assertThat(perm).isPresent();
            assertThat(perm.get().getBitIndex()).isNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Constructs a valid {@code Permission} with domain/resource/action parsed
     * from the {@code name} string (format: {@code domain:resource:action}).
     *
     * @param name     permission name in {@code domain:resource:action} format
     * @param bitIndex the bit index to assign, or {@code null} for unmapped
     * @return a transient {@code Permission} ready to persist
     */
    private static Permission buildPermission(String name, Integer bitIndex) {
        Permission p = new Permission();
        p.setName(name);
        p.parsePermissionName();
        p.setRegisteredAt(Instant.parse("2025-01-01T00:00:00Z"));
        p.setRegisteredByService("pos-security-service");
        p.setVersion("1.0");
        p.setBitIndex(bitIndex);
        return p;
    }
}
