package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.SupplierAuthConfigRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.service.model.AuthConfigRequest;
import com.positivity.supplier.service.model.AuthConfigView;
import com.positivity.supplier.service.model.CommercialAccountRequest;
import com.positivity.supplier.service.model.CommercialAccountView;
import com.positivity.supplier.service.model.EndpointBindingRequest;
import com.positivity.supplier.service.model.EndpointBindingView;
import com.positivity.supplier.service.model.PayloadCaptureLevel;
import com.positivity.supplier.service.model.ProfileSourceOfTruth;
import com.positivity.supplier.service.model.SupplierAccountRole;
import com.positivity.supplier.service.model.SupplierAuthType;
import com.positivity.supplier.service.model.VendorProfileRequest;
import com.positivity.supplier.service.model.VendorProfileView;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Acceptance behavior of {@code SupplierProfileAdminServiceImpl} against the real schema:
 * ADMIN-managed CRUD, YAML-managed rejection of every mutation type (ADR-0050 §6),
 * per-{@code SupplierAuthType} secret-reference completeness and plaintext rejection (§4),
 * account slot and binding-capability conflicts (§5/§3), referential auth-config guards, and
 * view mapping (auth views expose no reference values by shape).
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, SupplierProfileAdminServiceImpl.class})
class SupplierProfileAdminServiceImplTest {

    private static final UUID LOCATION_A = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    @Autowired
    private SupplierProfileAdminServiceImpl adminService;

    @Autowired
    private SupplierAuthConfigRepository authConfigRepository;

    @Autowired
    private SupplierProfileRepository profileRepository;

    // ── Profiles ────────────────────────────────────────────────────────────────────

    @Test
    void createsAdminManagedProfileAndListsBySupplierRef() {
        VendorProfileView zeta = adminService.createProfile(profileRequest("zeta-tyres"));
        VendorProfileView alpha = adminService.createProfile(profileRequest("alpha-tyres"));

        assertThat(zeta.sourceOfTruth()).isEqualTo(ProfileSourceOfTruth.ADMIN);
        assertThat(zeta.vendorProfileId()).isNotNull();
        assertThat(alpha.connectTimeoutMillis()).isEqualTo(5000);
        assertThat(adminService.listProfiles())
                .extracting(VendorProfileView::supplierRef)
                .containsExactly("alpha-tyres", "zeta-tyres");
    }

    @Test
    void duplicateSupplierRefConflicts() {
        adminService.createProfile(profileRequest("michelin-eu"));

        assertConflict(
                () -> adminService.createProfile(profileRequest("michelin-eu")),
                SupplierConflictException.SUPPLIER_REF_CONFLICT);
    }

    @Test
    void updatesAndDeletesAdminProfile() {
        VendorProfileView created = adminService.createProfile(profileRequest("michelin-eu"));

        VendorProfileView updated = adminService.updateProfile(
                created.vendorProfileId(),
                new VendorProfileRequest("michelin-eu", "Michelin EU (renamed)", false, true, null, null, null));
        assertThat(updated.displayName()).isEqualTo("Michelin EU (renamed)");
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.sandbox()).isTrue();
        assertThat(updated.connectTimeoutMillis()).isNull();

        adminService.deleteProfile(created.vendorProfileId());
        assertNotFound(
                () -> adminService.getProfile(created.vendorProfileId()), SupplierNotFoundException.PROFILE_NOT_FOUND);
    }

    @Test
    void unknownProfileIsNotFound() {
        UUID unknown = UUID.fromString("018f0000-0000-7000-8000-00000000f00d");
        assertNotFound(() -> adminService.getProfile(unknown), SupplierNotFoundException.PROFILE_NOT_FOUND);
        assertNotFound(
                () -> adminService.updateProfile(unknown, profileRequest("x")),
                SupplierNotFoundException.PROFILE_NOT_FOUND);
    }

    // ── Audit actor (ADR-0018/0024) ─────────────────────────────────────────────────

    /**
     * Interactive admin writes stamp the security-context username (the gateway's
     * {@code X-User}, surfaced by {@code GatewayAuthoritiesFilter} as authentication details)
     * — never the {@code system} fallback and never the YAML bootstrap actor.
     */
    @Test
    void interactiveWritesStampTheSecurityContextActorNotSystem() {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated("admin.jane", "n/a", List.of());
        authentication.setDetails(java.util.Map.of("username", "admin.jane"));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(authentication);
        try {
            UUID profileId =
                    adminService.createProfile(profileRequest("michelin-eu")).vendorProfileId();
            adminService.updateProfile(
                    profileId, new VendorProfileRequest("michelin-eu", "Michelin EU (v2)", true, false, null, null, null));
            profileRepository.flush();

            SupplierProfileEntity profile =
                    profileRepository.findById(profileId).orElseThrow();
            assertThat(profile.getCreatedBy()).isEqualTo("admin.jane");
            assertThat(profile.getUpdatedBy()).isEqualTo("admin.jane");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ── YAML-managed rejection (ADR-0050 §6) ────────────────────────────────────────

    @Test
    void yamlManagedProfileRejectsEveryMutationButAllowsReads() {
        UUID profileId = createYamlManagedProfile("yaml-vendor");

        assertYamlRejected(() -> adminService.updateProfile(profileId, profileRequest("yaml-vendor")));
        assertYamlRejected(() -> adminService.deleteProfile(profileId));
        assertYamlRejected(() -> adminService.createAuthConfig(profileId, bearerAuthRequest("a")));
        assertYamlRejected(() -> adminService.updateAuthConfig(profileId, profileId, bearerAuthRequest("a")));
        assertYamlRejected(() -> adminService.deleteAuthConfig(profileId, profileId));
        assertYamlRejected(() -> adminService.createAccount(profileId, billingRequest()));
        assertYamlRejected(() -> adminService.updateAccount(profileId, profileId, billingRequest()));
        assertYamlRejected(() -> adminService.deleteAccount(profileId, profileId));
        assertYamlRejected(() -> adminService.createBinding(profileId, bindingRequest("STOCK_INQUIRY", "a")));
        assertYamlRejected(
                () -> adminService.updateBinding(profileId, profileId, bindingRequest("STOCK_INQUIRY", "a")));
        assertYamlRejected(() -> adminService.deleteBinding(profileId, profileId));

        // Reads are unaffected.
        assertThat(adminService.getProfile(profileId).sourceOfTruth()).isEqualTo(ProfileSourceOfTruth.YAML);
        assertThat(adminService.listAuthConfigs(profileId)).isEmpty();
    }

    // ── Auth configs (ADR-0050 §4) ──────────────────────────────────────────────────

    @Test
    void authRefCompletenessIsEnforcedPerType() {
        UUID profileId = adminService.createProfile(profileRequest("michelin-eu")).vendorProfileId();

        // BASIC_PLUS_APIKEY without apiKeyRef.
        assertValidationFailure(
                () -> adminService.createAuthConfig(
                        profileId,
                        new AuthConfigRequest(
                                "basic",
                                SupplierAuthType.BASIC_PLUS_APIKEY,
                                "env:U",
                                "env:P",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)),
                SupplierValidationException.AUTH_REFS_INCOMPLETE);

        // OAUTH2_CLIENT_CREDENTIALS without clientSecretRef.
        assertValidationFailure(
                () -> adminService.createAuthConfig(
                        profileId,
                        new AuthConfigRequest(
                                "oauth",
                                SupplierAuthType.OAUTH2_CLIENT_CREDENTIALS,
                                null,
                                null,
                                null,
                                null,
                                "env:TOKEN_URL",
                                "env:CLIENT_ID",
                                null,
                                null)),
                SupplierValidationException.AUTH_REFS_INCOMPLETE);

        // BEARER without bearerTokenRef.
        assertValidationFailure(
                () -> adminService.createAuthConfig(
                        profileId,
                        new AuthConfigRequest(
                                "bearer", SupplierAuthType.BEARER, null, null, null, null, null, null, null, null)),
                SupplierValidationException.AUTH_REFS_INCOMPLETE);

        // BEARER with a ref the type does not use.
        assertValidationFailure(
                () -> adminService.createAuthConfig(
                        profileId,
                        new AuthConfigRequest(
                                "bearer", SupplierAuthType.BEARER, "env:U", null, null, null, null, null, null,
                                "env:B")),
                SupplierValidationException.AUTH_REFS_INCOMPLETE);
    }

    @Test
    void plaintextLookingSecretValueIsRejected() {
        UUID profileId = adminService.createProfile(profileRequest("michelin-eu")).vendorProfileId();

        assertValidationFailure(
                () -> adminService.createAuthConfig(
                        profileId,
                        new AuthConfigRequest(
                                "bearer", SupplierAuthType.BEARER, null, null, null, null, null, null, null,
                                "hunter2")),
                SupplierValidationException.SECRET_REF_MALFORMED);
    }

    @Test
    void authViewsCarryNoReferenceValuesAndPersistRefsWriteOnly() {
        UUID profileId = adminService.createProfile(profileRequest("michelin-eu")).vendorProfileId();

        AuthConfigView view = adminService.createAuthConfig(profileId, bearerAuthRequest("s2s-bearer"));

        assertThat(view.name()).isEqualTo("s2s-bearer");
        assertThat(view.type()).isEqualTo(SupplierAuthType.BEARER);
        // The view record has no reference fields at all — verify the reference reached the
        // entity (write-only path) without ever appearing on the read model.
        assertThat(authConfigRepository
                        .findByVendorProfileIdAndName(profileId, "s2s-bearer")
                        .orElseThrow()
                        .getBearerTokenRef())
                .isEqualTo("env:SUPPLIER_BEARER");
        assertThat(adminService.listAuthConfigs(profileId)).containsExactly(view);
    }

    @Test
    void apiKeyHeaderRoundTripsAsPlainConfigDataWhileRefsStayWriteOnly() {
        UUID profileId = adminService.createProfile(profileRequest("michelin-eu")).vendorProfileId();

        AuthConfigView view = adminService.createAuthConfig(
                profileId,
                new AuthConfigRequest(
                        "basic-apikey",
                        SupplierAuthType.BASIC_PLUS_APIKEY,
                        "env:U",
                        "env:P",
                        "env:K",
                        "x-api-key",
                        null,
                        null,
                        null,
                        null));

        // apiKeyHeader is a plain header NAME (config data, not a secret) — it round-trips
        // onto the view while apiKeyRef stays write-only.
        assertThat(view.apiKeyHeader()).isEqualTo("x-api-key");
        assertThat(adminService.listAuthConfigs(profileId).getFirst().apiKeyHeader())
                .isEqualTo("x-api-key");
        assertThat(authConfigRepository
                        .findByVendorProfileIdAndName(profileId, "basic-apikey")
                        .orElseThrow()
                        .getApiKeyHeader())
                .isEqualTo("x-api-key");
    }

    @Test
    void duplicateAuthNameConflictsAndDeleteWhileReferencedIsRejected() {
        UUID profileId = adminService.createProfile(profileRequest("michelin-eu")).vendorProfileId();
        AuthConfigView auth = adminService.createAuthConfig(profileId, bearerAuthRequest("s2s-bearer"));

        assertConflict(
                () -> adminService.createAuthConfig(profileId, bearerAuthRequest("s2s-bearer")),
                SupplierConflictException.AUTH_CONFIG_NAME_CONFLICT);

        adminService.createBinding(profileId, bindingRequest("STOCK_INQUIRY", "s2s-bearer"));
        assertConflict(
                () -> adminService.deleteAuthConfig(profileId, auth.authConfigId()),
                SupplierConflictException.AUTH_CONFIG_IN_USE);

        // Renaming while referenced would orphan the binding's authConfigName — also rejected.
        AuthConfigRequest renamed = new AuthConfigRequest(
                "renamed", SupplierAuthType.BEARER, null, null, null, null, null, null, null, "env:SUPPLIER_BEARER");
        assertConflict(
                () -> adminService.updateAuthConfig(profileId, auth.authConfigId(), renamed),
                SupplierConflictException.AUTH_CONFIG_IN_USE);

        // After the binding goes away, delete succeeds.
        EndpointBindingView binding =
                adminService.listBindings(profileId).getFirst();
        adminService.deleteBinding(profileId, binding.bindingId());
        adminService.deleteAuthConfig(profileId, auth.authConfigId());
        assertThat(adminService.listAuthConfigs(profileId)).isEmpty();
    }

    // ── Accounts (ADR-0050 §5) ──────────────────────────────────────────────────────

    @Test
    void accountSlotsAreEnforcedAndListedBillingFirst() {
        UUID profileId = adminService.createProfile(profileRequest("michelin-eu")).vendorProfileId();
        CommercialAccountView billing = adminService.createAccount(profileId, billingRequest());
        adminService.createAccount(
                profileId,
                new CommercialAccountRequest(SupplierAccountRole.DELIVERY, "0000067890", "31", LOCATION_A));

        assertConflict(
                () -> adminService.createAccount(profileId, billingRequest()),
                SupplierConflictException.ACCOUNT_SLOT_CONFLICT);
        assertConflict(
                () -> adminService.createAccount(
                        profileId,
                        new CommercialAccountRequest(SupplierAccountRole.DELIVERY, "0000067899", "31", LOCATION_A)),
                SupplierConflictException.ACCOUNT_SLOT_CONFLICT);

        assertThat(adminService.listAccounts(profileId))
                .extracting(CommercialAccountView::role)
                .containsExactly(SupplierAccountRole.BILLING, SupplierAccountRole.DELIVERY);

        // Updating the billing account itself (same slot) is legal.
        CommercialAccountView updated = adminService.updateAccount(
                profileId,
                billing.accountId(),
                new CommercialAccountRequest(SupplierAccountRole.BILLING, "0000054321", null, null));
        assertThat(updated.accountNumber()).isEqualTo("0000054321");
        assertThat(updated.agencyCode()).isNull();
    }

    // ── Bindings (ADR-0050 §3) ──────────────────────────────────────────────────────

    @Test
    void bindingValidatesCanonicalKeysAuthNameAndSchedule() {
        UUID profileId = adminService.createProfile(profileRequest("michelin-eu")).vendorProfileId();
        adminService.createAuthConfig(profileId, bearerAuthRequest("s2s-bearer"));

        assertValidationFailure(
                () -> adminService.createBinding(profileId, bindingRequest("NOT_A_CAPABILITY", "s2s-bearer")),
                SupplierValidationException.UNKNOWN_CAPABILITY);
        assertValidationFailure(
                () -> adminService.createBinding(
                        profileId,
                        new EndpointBindingRequest(
                                "STOCK_INQUIRY",
                                "NOT_A_FAMILY",
                                "A2_5",
                                "https://api.example",
                                "/inquiry",
                                "s2s-bearer",
                                null,
                                true,
                                null)),
                SupplierValidationException.UNKNOWN_PROTOCOL_FAMILY);
        assertValidationFailure(
                () -> adminService.createBinding(
                        profileId,
                        new EndpointBindingRequest(
                                "STOCK_INQUIRY",
                                "TEST",
                                "A2_5",
                                "https://api.example",
                                "/inquiry",
                                "s2s-bearer",
                                null,
                                true,
                                null)),
                SupplierValidationException.UNKNOWN_PROTOCOL_FAMILY);
        assertValidationFailure(
                () -> adminService.createBinding(profileId, bindingRequest("STOCK_INQUIRY", "no-such-auth")),
                SupplierValidationException.BINDING_AUTH_CONFIG_UNKNOWN);
        assertValidationFailure(
                () -> adminService.createBinding(
                        profileId,
                        new EndpointBindingRequest(
                                "PRICE_CATALOG",
                                "EDIWHEEL_B",
                                "B4_0",
                                "https://api.example",
                                "/pricat",
                                "s2s-bearer",
                                "not-a-cron",
                                true,
                                null)),
                SupplierValidationException.SCHEDULE_INVALID);
    }

    @Test
    void oneBindingPerCapabilityAndFullRoundTrip() {
        UUID profileId = adminService.createProfile(profileRequest("michelin-eu")).vendorProfileId();
        adminService.createAuthConfig(profileId, bearerAuthRequest("s2s-bearer"));

        EndpointBindingView created = adminService.createBinding(
                profileId,
                new EndpointBindingRequest(
                        "PRICE_CATALOG",
                        "EDIWHEEL_B",
                        "B4_0",
                        "https://api.example",
                        "/catalog/B4_0/pricat",
                        "s2s-bearer",
                        "0 0 4 * * *",
                        true,
                        PayloadCaptureLevel.REDACTED));

        assertThat(created.capability()).isEqualTo("PRICE_CATALOG");
        assertThat(created.protocolFamily()).isEqualTo("EDIWHEEL_B");
        assertThat(created.version()).isEqualTo("B4_0");
        assertThat(created.schedule()).isEqualTo("0 0 4 * * *");
        assertThat(created.captureLevel()).isEqualTo(PayloadCaptureLevel.REDACTED);

        assertConflict(
                () -> adminService.createBinding(
                        profileId,
                        new EndpointBindingRequest(
                                "PRICE_CATALOG",
                                "EDIWHEEL_B",
                                "B4_0",
                                "https://other.example",
                                "/pricat",
                                "s2s-bearer",
                                null,
                                true,
                                null)),
                SupplierConflictException.BINDING_CAPABILITY_CONFLICT);

        EndpointBindingView updated = adminService.updateBinding(
                profileId,
                created.bindingId(),
                new EndpointBindingRequest(
                        "PRICE_CATALOG",
                        "EDIWHEEL_B",
                        "B4_0",
                        "https://api.example",
                        "/catalog/B4_0/pricat",
                        "s2s-bearer",
                        null,
                        false,
                        null));
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.schedule()).isNull();
        assertThat(adminService.listBindings(profileId)).containsExactly(updated);
    }

    // ── Fixtures and assertions ─────────────────────────────────────────────────────

    private UUID createYamlManagedProfile(String supplierRef) {
        UUID profileId = adminService.createProfile(profileRequest(supplierRef)).vendorProfileId();
        // Flip the source marker the way the YAML reconciler would own it.
        SupplierProfileEntity profile = profileRepository.findById(profileId).orElseThrow();
        profile.setSourceOfTruth(com.positivity.supplier.internal.enums.ProfileSourceOfTruth.YAML);
        profileRepository.saveAndFlush(profile);
        return profileId;
    }

    private static VendorProfileRequest profileRequest(String supplierRef) {
        return new VendorProfileRequest(supplierRef, "Display " + supplierRef, true, false, 5000, 30000, 3);
    }

    private static AuthConfigRequest bearerAuthRequest(String name) {
        return new AuthConfigRequest(
                name, SupplierAuthType.BEARER, null, null, null, null, null, null, null, "env:SUPPLIER_BEARER");
    }

    private static CommercialAccountRequest billingRequest() {
        return new CommercialAccountRequest(SupplierAccountRole.BILLING, "0000012345", "31", null);
    }

    private static EndpointBindingRequest bindingRequest(String capability, String authName) {
        return new EndpointBindingRequest(
                capability, "EDIWHEEL_A25", "A2_5", "https://api.example", "/inquiry", authName, null, true, null);
    }

    private static void assertYamlRejected(ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(SupplierConflictException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConflictException.PROFILE_YAML_MANAGED)
                .hasMessageContaining("YAML");
    }

    private static void assertConflict(ThrowingCallable callable, String expectedCode) {
        assertThatThrownBy(callable)
                .isInstanceOf(SupplierConflictException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }

    private static void assertValidationFailure(ThrowingCallable callable, String expectedCode) {
        assertThatThrownBy(callable)
                .isInstanceOf(SupplierValidationException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }

    private static void assertNotFound(ThrowingCallable callable, String expectedCode) {
        assertThatThrownBy(callable)
                .isInstanceOf(SupplierNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }
}
