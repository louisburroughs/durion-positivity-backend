package com.positivity.supplier.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.config.SupplierProfileProperties.Accounts;
import com.positivity.supplier.internal.config.SupplierProfileProperties.AuthSpec;
import com.positivity.supplier.internal.config.SupplierProfileProperties.Billing;
import com.positivity.supplier.internal.config.SupplierProfileProperties.BindingSpec;
import com.positivity.supplier.internal.config.SupplierProfileProperties.Delivery;
import com.positivity.supplier.internal.config.SupplierProfileProperties.ProfileSpec;
import com.positivity.supplier.internal.config.SupplierProfileProperties.ProtocolDefaults;
import com.positivity.supplier.internal.config.SupplierProfileProperties.Retry;
import com.positivity.supplier.internal.config.SupplierProfileProperties.Sandbox;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierProfilePersistenceFixtures;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import com.positivity.supplier.internal.enums.RetryBackoff;
import com.positivity.supplier.internal.enums.SupplierAccountRole;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.repository.SupplierAccountRepository;
import com.positivity.supplier.internal.repository.SupplierAuthConfigRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.service.EnvSecretReferenceResolver;
import com.positivity.supplier.internal.service.SecretSchemeRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Reconciliation semantics of {@link SupplierYamlBootstrap} (ADR-0050 §6): create on first
 * run, overwrite children to match YAML exactly, disable (never delete) previously-YAML
 * profiles absent from current YAML, never touch ADMIN profiles, idempotent second run, audit
 * actor {@code system:yaml-bootstrap}, and startup rejection of plaintext-looking secrets and
 * ADMIN collisions.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_bootstrap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class SupplierYamlBootstrapTest {

    private static final UUID LOCATION_A = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID LOCATION_B = UUID.fromString("018f0000-0000-7000-8000-0000000000b2");

    @Autowired
    private SupplierProfileRepository profileRepository;

    @Autowired
    private SupplierAuthConfigRepository authConfigRepository;

    @Autowired
    private SupplierAccountRepository accountRepository;

    @Autowired
    private SupplierEndpointBindingRepository bindingRepository;

    private SupplierYamlBootstrap bootstrap;

    @BeforeEach
    void createBootstrap() {
        bootstrap = new SupplierYamlBootstrap(
                new SupplierProfileProperties(null),
                profileRepository,
                authConfigRepository,
                accountRepository,
                bindingRepository,
                new SecretSchemeRegistry(List.of(new EnvSecretReferenceResolver())));
    }

    @Test
    void firstRunCreatesYamlProfileAggregateWithBootstrapActor() {
        bootstrap.reconcile(properties(michelinSpec()));

        SupplierProfileEntity profile =
                profileRepository.findBySupplierRef("michelin-eu").orElseThrow();
        assertThat(profile.getSourceOfTruth()).isEqualTo(ProfileSourceOfTruth.YAML);
        assertThat(profile.isEnabled()).isTrue();
        assertThat(profile.getDisplayName()).isEqualTo("Michelin Europe");
        assertThat(profile.getConnectTimeoutMs()).isEqualTo(5000);
        assertThat(profile.getReadTimeoutMs()).isEqualTo(30000);
        assertThat(profile.getRetryMaxAttempts()).isEqualTo(3);
        assertThat(profile.getRetryBackoff()).isEqualTo(RetryBackoff.EXPONENTIAL);
        assertThat(profile.isSandbox()).isTrue();
        assertThat(profile.getSandboxBaseUrlOverride()).isEqualTo("https://sandbox.api.michelin.example");
        assertThat(profile.getCreatedBy()).isEqualTo(SupplierYamlBootstrap.BOOTSTRAP_ACTOR);
        assertThat(profile.getUpdatedBy()).isEqualTo(SupplierYamlBootstrap.BOOTSTRAP_ACTOR);

        UUID profileId = profile.getVendorProfileId();
        List<SupplierAuthConfigEntity> authConfigs =
                authConfigRepository.findByVendorProfileIdOrderByNameAsc(profileId);
        assertThat(authConfigs).hasSize(1);
        assertThat(authConfigs.getFirst().getType()).isEqualTo(SupplierAuthType.BASIC_PLUS_APIKEY);
        assertThat(authConfigs.getFirst().getPasswordRef()).isEqualTo("env:MICHELIN_EDI_PASSWORD");
        assertThat(authConfigs.getFirst().getApiKeyHeader()).isEqualTo("apikey");
        assertThat(authConfigs.getFirst().getCreatedBy()).isEqualTo(SupplierYamlBootstrap.BOOTSTRAP_ACTOR);

        List<SupplierAccountEntity> accounts =
                accountRepository.findByVendorProfileIdOrderByRoleAscAccountNumberAsc(profileId);
        assertThat(accounts)
                .extracting(SupplierAccountEntity::getRole, SupplierAccountEntity::getAccountNumber)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SupplierAccountRole.BILLING, "0000012345"),
                        org.assertj.core.groups.Tuple.tuple(SupplierAccountRole.DELIVERY, "0000067890"));

        List<SupplierEndpointBindingEntity> bindings =
                bindingRepository.findByVendorProfileIdOrderByCapabilityAsc(profileId);
        assertThat(bindings).hasSize(2);
        SupplierEndpointBindingEntity stockBinding = bindingRepository
                .findByVendorProfileIdAndCapability(profileId, SupplierCapability.STOCK_INQUIRY)
                .orElseThrow();
        assertThat(stockBinding.getProtocolFamily()).isEqualTo(ProtocolFamily.EDIWHEEL_A25);
        assertThat(stockBinding.getProtocolVersion()).isEqualTo("A2_5");
        assertThat(stockBinding.getAuthConfigName()).isEqualTo("ediwheel-basic");
        SupplierEndpointBindingEntity pricatBinding = bindingRepository
                .findByVendorProfileIdAndCapability(profileId, SupplierCapability.PRICE_CATALOG)
                .orElseThrow();
        assertThat(pricatBinding.getProtocolFamily()).isEqualTo(ProtocolFamily.EDIWHEEL_B);
        assertThat(pricatBinding.getScheduleCron()).isEqualTo("0 0 4 * * *");
    }

    @Test
    void secondIdenticalRunIsIdempotent() {
        bootstrap.reconcile(properties(michelinSpec()));
        SupplierProfileEntity profile =
                profileRepository.findBySupplierRef("michelin-eu").orElseThrow();
        UUID profileId = profile.getVendorProfileId();
        Long profileVersion = profile.getVersion();
        Long authVersion = authConfigRepository
                .findByVendorProfileIdAndName(profileId, "ediwheel-basic")
                .orElseThrow()
                .getVersion();

        bootstrap.reconcile(properties(michelinSpec()));

        SupplierProfileEntity after =
                profileRepository.findBySupplierRef("michelin-eu").orElseThrow();
        assertThat(after.getVendorProfileId()).isEqualTo(profileId);
        assertThat(after.getVersion()).isEqualTo(profileVersion);
        assertThat(authConfigRepository
                        .findByVendorProfileIdAndName(profileId, "ediwheel-basic")
                        .orElseThrow()
                        .getVersion())
                .isEqualTo(authVersion);
        assertThat(bindingRepository.findByVendorProfileIdOrderByCapabilityAsc(profileId))
                .hasSize(2);
    }

    @Test
    void changedYamlOverwritesProfileAndChildrenExactly() {
        bootstrap.reconcile(properties(michelinSpec()));
        UUID profileId =
                profileRepository.findBySupplierRef("michelin-eu").orElseThrow().getVendorProfileId();

        // Changed display name, rotated password ref, delivery moved to another location,
        // PRICAT binding dropped, stock inquiry URL changed.
        ProfileSpec changed = new ProfileSpec(
                "michelin-eu",
                "Michelin Europe (v2)",
                true,
                new ProtocolDefaults("EDIWHEEL_A25", 7000, 30000, new Retry(3, "EXPONENTIAL")),
                new Accounts(
                        new Billing("0000012345", "31"),
                        List.of(new Delivery(LOCATION_B, "0000067999", "31")),
                        null,
                        null),
                List.of(basicAuthSpec("env:MICHELIN_EDI_PASSWORD_V2")),
                List.of(new BindingSpec(
                        "STOCK_INQUIRY",
                        null,
                        "A2_5",
                        "https://api2.michelin.example",
                        "/A2_5/inquiry",
                        "ediwheel-basic",
                        null,
                        null,
                        null)),
                new Sandbox(false, null));

        bootstrap.reconcile(properties(changed));

        SupplierProfileEntity profile =
                profileRepository.findBySupplierRef("michelin-eu").orElseThrow();
        assertThat(profile.getVendorProfileId()).isEqualTo(profileId);
        assertThat(profile.getDisplayName()).isEqualTo("Michelin Europe (v2)");
        assertThat(profile.getConnectTimeoutMs()).isEqualTo(7000);
        assertThat(profile.isSandbox()).isFalse();
        assertThat(profile.getSandboxBaseUrlOverride()).isNull();
        assertThat(profile.getUpdatedBy()).isEqualTo(SupplierYamlBootstrap.BOOTSTRAP_ACTOR);

        assertThat(authConfigRepository
                        .findByVendorProfileIdAndName(profileId, "ediwheel-basic")
                        .orElseThrow()
                        .getPasswordRef())
                .isEqualTo("env:MICHELIN_EDI_PASSWORD_V2");
        assertThat(accountRepository.findByVendorProfileIdOrderByRoleAscAccountNumberAsc(profileId))
                .extracting(SupplierAccountEntity::getRole, SupplierAccountEntity::getDeliveryLocationId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SupplierAccountRole.BILLING, null),
                        org.assertj.core.groups.Tuple.tuple(SupplierAccountRole.DELIVERY, LOCATION_B));
        List<SupplierEndpointBindingEntity> bindings =
                bindingRepository.findByVendorProfileIdOrderByCapabilityAsc(profileId);
        assertThat(bindings).hasSize(1);
        assertThat(bindings.getFirst().getCapability()).isEqualTo(SupplierCapability.STOCK_INQUIRY);
        assertThat(bindings.getFirst().getBaseUrl()).isEqualTo("https://api2.michelin.example");
    }

    @Test
    void profileRemovedFromYamlIsDisabledNeverDeleted() {
        bootstrap.reconcile(properties(michelinSpec()));
        UUID profileId =
                profileRepository.findBySupplierRef("michelin-eu").orElseThrow().getVendorProfileId();

        bootstrap.reconcile(new SupplierProfileProperties(List.of()));

        SupplierProfileEntity profile = profileRepository.findById(profileId).orElseThrow();
        assertThat(profile.isEnabled()).isFalse();
        assertThat(profile.getSourceOfTruth()).isEqualTo(ProfileSourceOfTruth.YAML);
        assertThat(profile.getUpdatedBy()).isEqualTo(SupplierYamlBootstrap.BOOTSTRAP_ACTOR);
        // History preserved: children stay in place, they simply stop resolving.
        assertThat(authConfigRepository.findByVendorProfileIdOrderByNameAsc(profileId))
                .isNotEmpty();
    }

    @Test
    void adminManagedProfilesAreNeverTouched() {
        SupplierProfileEntity admin =
                profileRepository.saveAndFlush(SupplierProfilePersistenceFixtures.profile("admin-vendor"));
        Long adminVersion = admin.getVersion();

        bootstrap.reconcile(properties(michelinSpec()));

        SupplierProfileEntity after =
                profileRepository.findBySupplierRef("admin-vendor").orElseThrow();
        assertThat(after.getSourceOfTruth()).isEqualTo(ProfileSourceOfTruth.ADMIN);
        assertThat(after.isEnabled()).isTrue();
        assertThat(after.getVersion()).isEqualTo(adminVersion);
        assertThat(after.getCreatedBy()).isEqualTo("system");
    }

    @Test
    void yamlKeyCollidingWithAdminProfileFailsStartup() {
        profileRepository.saveAndFlush(SupplierProfilePersistenceFixtures.profile("michelin-eu"));

        assertThatThrownBy(() -> bootstrap.reconcile(properties(michelinSpec())))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.YAML_BOOTSTRAP_INVALID)
                .hasMessageContaining("ADMIN-managed");
    }

    @Test
    void plaintextLookingSecretFailsStartupWithClearError() {
        ProfileSpec spec = new ProfileSpec(
                "michelin-eu",
                "Michelin Europe",
                null,
                null,
                null,
                List.of(new AuthSpec(
                        "ediwheel-basic",
                        "BASIC_PLUS_APIKEY",
                        "env:USER",
                        "hunter2",
                        "apikey",
                        "env:APIKEY",
                        null,
                        null,
                        null,
                        null)),
                null,
                null);

        assertThatThrownBy(() -> bootstrap.reconcile(properties(spec)))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.YAML_BOOTSTRAP_INVALID)
                .hasMessageContaining("scheme:key");
        assertThat(profileRepository.findBySupplierRef("michelin-eu")).isEmpty();
    }

    /**
     * The YAML path must enforce the same scheme allowlist as the admin API. {@code user:hunter2}
     * is shape-legal ({@code scheme:key}) and a plaintext password, so before the allowlist it
     * reconciled successfully and failed only on the first outbound call — the deferred credential
     * leak ADR-0050 §4/§6 forbids.
     */
    @Test
    void wellFormedSecretRefInUnsupportedSchemeFailsStartup() {
        ProfileSpec spec = new ProfileSpec(
                "michelin-eu",
                "Michelin Europe",
                null,
                null,
                null,
                List.of(new AuthSpec(
                        "ediwheel-basic",
                        "BASIC_PLUS_APIKEY",
                        "env:USER",
                        "user:hunter2",
                        "apikey",
                        "env:APIKEY",
                        null,
                        null,
                        null,
                        null)),
                null,
                null);

        assertThatThrownBy(() -> bootstrap.reconcile(properties(spec)))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.YAML_BOOTSTRAP_INVALID)
                .hasMessageContaining("env:")
                // Neither half of the rejected value may reach the startup log: for a plaintext
                // password containing a colon, the "scheme" is part of the credential.
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("user");
        assertThat(profileRepository.findBySupplierRef("michelin-eu")).isEmpty();
    }

    @Test
    void danglingBindingAuthNameFailsStartup() {
        ProfileSpec spec = new ProfileSpec(
                "michelin-eu",
                "Michelin Europe",
                null,
                null,
                null,
                List.of(basicAuthSpec("env:MICHELIN_EDI_PASSWORD")),
                List.of(new BindingSpec(
                        "STOCK_INQUIRY",
                        "EDIWHEEL_A25",
                        "A2_5",
                        "https://api.michelin.example",
                        "/A2_5/inquiry",
                        "no-such-auth",
                        null,
                        null,
                        null)),
                null);

        assertThatThrownBy(() -> bootstrap.reconcile(properties(spec)))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.YAML_BOOTSTRAP_INVALID)
                .hasMessageContaining("no-such-auth");
    }

    @Test
    void unknownCanonicalKeysFailStartup() {
        ProfileSpec badCapability = new ProfileSpec(
                "michelin-eu",
                "Michelin Europe",
                null,
                null,
                null,
                List.of(basicAuthSpec("env:MICHELIN_EDI_PASSWORD")),
                List.of(new BindingSpec(
                        "NOT_A_CAPABILITY",
                        "EDIWHEEL_A25",
                        "A2_5",
                        "https://api.michelin.example",
                        "/A2_5/inquiry",
                        "ediwheel-basic",
                        null,
                        null,
                        null)),
                null);

        assertThatThrownBy(() -> bootstrap.reconcile(properties(badCapability)))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasMessageContaining("NOT_A_CAPABILITY");
    }

    @Test
    void reAddedYamlProfileIsReEnabledWithSandboxOverlayRestored() {
        // ADR-0050 §6: removal from YAML disables; re-adding the profile to the YAML must
        // reconcile it back to enabled on the SAME row (identity preserved), including the
        // sandbox overlay round-tripping back in.
        bootstrap.reconcile(properties(michelinSpec()));
        UUID profileId =
                profileRepository.findBySupplierRef("michelin-eu").orElseThrow().getVendorProfileId();
        bootstrap.reconcile(new SupplierProfileProperties(List.of()));
        assertThat(profileRepository.findById(profileId).orElseThrow().isEnabled())
                .isFalse();

        bootstrap.reconcile(properties(michelinSpec()));

        SupplierProfileEntity reAdded = profileRepository.findById(profileId).orElseThrow();
        assertThat(reAdded.isEnabled()).isTrue();
        assertThat(reAdded.getSourceOfTruth()).isEqualTo(ProfileSourceOfTruth.YAML);
        assertThat(reAdded.isSandbox()).isTrue();
        assertThat(reAdded.getSandboxBaseUrlOverride()).isEqualTo("https://sandbox.api.michelin.example");
        assertThat(reAdded.getUpdatedBy()).isEqualTo(SupplierYamlBootstrap.BOOTSTRAP_ACTOR);
        // Same natural key, same identity — no second profile appeared.
        assertThat(profileRepository.findAll()).hasSize(1);
    }

    @Test
    void sameDeliveryLocationAcrossTwoYamlProfilesIsLegal() {
        // The one-delivery-account-per-location rule is scoped PER PROFILE (ADR-0050 §5):
        // two suppliers may both deliver to the same pos-location.
        ProfileSpec second = new ProfileSpec(
                "conti-eu",
                "Continental Europe",
                null,
                null,
                new Accounts(
                        new Billing("0000099999", "31"),
                        List.of(new Delivery(LOCATION_A, "0000088888", "31")),
                        null,
                        null),
                List.of(basicAuthSpec("env:CONTI_EDI_PASSWORD")),
                null,
                null);

        bootstrap.reconcile(properties(michelinSpec(), second));

        UUID michelinId =
                profileRepository.findBySupplierRef("michelin-eu").orElseThrow().getVendorProfileId();
        UUID contiId =
                profileRepository.findBySupplierRef("conti-eu").orElseThrow().getVendorProfileId();
        assertThat(accountRepository
                        .findByVendorProfileIdAndRoleAndDeliveryLocationId(
                                michelinId, SupplierAccountRole.DELIVERY, LOCATION_A)
                        .orElseThrow()
                        .getAccountNumber())
                .isEqualTo("0000067890");
        assertThat(accountRepository
                        .findByVendorProfileIdAndRoleAndDeliveryLocationId(
                                contiId, SupplierAccountRole.DELIVERY, LOCATION_A)
                        .orElseThrow()
                        .getAccountNumber())
                .isEqualTo("0000088888");
    }

    @Test
    void adminMutationOfYamlProfileIsRejectedAndNextReconcileStaysIdentical() {
        // End-to-end authority proof (ADR-0050 §6): the admin API cannot dent a YAML-managed
        // profile, and the next reconcile against unchanged YAML remains a no-op — the YAML
        // stays the single source of truth across both write paths.
        bootstrap.reconcile(properties(michelinSpec()));
        SupplierProfileEntity profile =
                profileRepository.findBySupplierRef("michelin-eu").orElseThrow();
        UUID profileId = profile.getVendorProfileId();
        Long profileVersion = profile.getVersion();

        var adminService = new com.positivity.supplier.internal.service.SupplierProfileAdminServiceImpl(
                profileRepository,
                authConfigRepository,
                accountRepository,
                bindingRepository,
                new SecretSchemeRegistry(List.of(new EnvSecretReferenceResolver())),
                // A discarding publisher: these assertions are about YAML authority rejecting the write
                // before anything is published. If a credential-invalidation event ever escaped a rejected
                // mutation that would be a defect, and SupplierProfileAdminServiceImplTest is where it is
                // caught -- pinning the negative here as well would duplicate that without adding cover.
                event -> {});
        assertThatThrownBy(() -> adminService.updateProfile(
                        profileId,
                        new com.positivity.supplier.service.model.VendorProfileRequest(
                                "michelin-eu", "Hijacked", false, false, 1, 1, 0, null, null)))
                .isInstanceOf(com.positivity.supplier.internal.exception.SupplierConflictException.class)
                .hasFieldOrPropertyWithValue(
                        "code",
                        com.positivity.supplier.internal.exception.SupplierConflictException.PROFILE_YAML_MANAGED);
        assertThatThrownBy(() -> adminService.deleteBinding(
                        profileId,
                        bindingRepository
                                .findByVendorProfileIdAndCapability(profileId, SupplierCapability.STOCK_INQUIRY)
                                .orElseThrow()
                                .getId()))
                .hasFieldOrPropertyWithValue(
                        "code",
                        com.positivity.supplier.internal.exception.SupplierConflictException.PROFILE_YAML_MANAGED);

        bootstrap.reconcile(properties(michelinSpec()));

        SupplierProfileEntity after = profileRepository.findById(profileId).orElseThrow();
        assertThat(after.getDisplayName()).isEqualTo("Michelin Europe");
        assertThat(after.isEnabled()).isTrue();
        assertThat(after.getVersion()).isEqualTo(profileVersion);
        assertThat(bindingRepository.findByVendorProfileIdOrderByCapabilityAsc(profileId))
                .hasSize(2);
    }

    @Test
    void nullPropertiesAndEmptyProfilesAreNoOps() {
        bootstrap.reconcile(null);
        bootstrap.reconcile(new SupplierProfileProperties(null));
        bootstrap.reconcile(new SupplierProfileProperties(List.of()));

        assertThat(profileRepository.findAll()).isEmpty();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────

    private static SupplierProfileProperties properties(ProfileSpec... specs) {
        return new SupplierProfileProperties(List.of(specs));
    }

    private static AuthSpec basicAuthSpec(String passwordRef) {
        return new AuthSpec(
                "ediwheel-basic",
                "BASIC_PLUS_APIKEY",
                "env:MICHELIN_EDI_USER",
                passwordRef,
                "apikey",
                "env:MICHELIN_EDI_APIKEY",
                null,
                null,
                null,
                null);
    }

    /** Mirrors the architecture doc §7 YAML example, families per binding. */
    private static ProfileSpec michelinSpec() {
        return new ProfileSpec(
                "michelin-eu",
                "Michelin Europe",
                null,
                new ProtocolDefaults("EDIWHEEL_A25", 5000, 30000, new Retry(3, "EXPONENTIAL")),
                new Accounts(
                        new Billing("0000012345", "31"),
                        List.of(new Delivery(LOCATION_A, "0000067890", "31")),
                        "MICHELIN",
                        "91"),
                List.of(basicAuthSpec("env:MICHELIN_EDI_PASSWORD")),
                List.of(
                        new BindingSpec(
                                "STOCK_INQUIRY",
                                null,
                                "A2_5",
                                "https://api.michelin.example",
                                "/A2_5/inquiry",
                                "ediwheel-basic",
                                null,
                                null,
                                null),
                        new BindingSpec(
                                "PRICE_CATALOG",
                                "EDIWHEEL_B",
                                "B4_0",
                                "https://api.michelin.example",
                                "/catalog/B4_0/pricat",
                                "ediwheel-basic",
                                "0 0 4 * * *",
                                true,
                                "REDACTED")),
                new Sandbox(true, "https://sandbox.api.michelin.example"));
    }
}
