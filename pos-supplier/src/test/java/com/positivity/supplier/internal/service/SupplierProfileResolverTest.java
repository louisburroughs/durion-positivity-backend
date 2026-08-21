package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierProfilePersistenceFixtures;
import com.positivity.supplier.internal.enums.SupplierAccountRole;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.repository.SupplierAccountRepository;
import com.positivity.supplier.internal.repository.SupplierAuthConfigRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Typed-failure matrix of {@link SupplierProfileResolver} (ADR-0050 §3/§5): every
 * configuration gap fails with a {@link SupplierConfigurationException} carrying the
 * matching code — unknown supplier, disabled profile, absent/disabled binding, missing
 * billing account, missing delivery mapping — plus the happy paths returning the binding with
 * its adapter-registry triple and the resolved party accounts.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_resolver;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestClockConfig.class, SupplierProfileResolver.class})
class SupplierProfileResolverTest {

    private static final SupplierRef MICHELIN = new SupplierRef("michelin-eu");
    private static final UUID LOCATION_A = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID LOCATION_UNMAPPED = UUID.fromString("018f0000-0000-7000-8000-00000000dead");

    @Autowired
    private SupplierProfileRepository profileRepository;

    @Autowired
    private SupplierAuthConfigRepository authConfigRepository;

    @Autowired
    private SupplierAccountRepository accountRepository;

    @Autowired
    private SupplierEndpointBindingRepository bindingRepository;

    @Autowired
    private SupplierProfileResolver resolver;

    private SupplierProfileEntity profile;

    @BeforeEach
    void seedProfile() {
        profile = profileRepository.save(SupplierProfilePersistenceFixtures.profile("michelin-eu"));
        authConfigRepository.save(SupplierProfilePersistenceFixtures.basicAuth(vendorProfileId(), "ediwheel-basic"));
        accountRepository.save(SupplierProfilePersistenceFixtures.billingAccount(vendorProfileId(), "0000012345"));
        accountRepository.save(
                SupplierProfilePersistenceFixtures.deliveryAccount(vendorProfileId(), LOCATION_A, "0000067890"));
        bindingRepository.save(SupplierProfilePersistenceFixtures.binding(
                vendorProfileId(), SupplierCapability.STOCK_INQUIRY, "ediwheel-basic"));
    }

    private UUID vendorProfileId() {
        return profile.getVendorProfileId();
    }

    // ── isConfigured ────────────────────────────────────────────────────────────────

    @Test
    void isConfiguredReflectsProfileAndBindingState() {
        assertThat(resolver.isConfigured(MICHELIN, SupplierCapability.STOCK_INQUIRY))
                .isTrue();
        assertThat(resolver.isConfigured(MICHELIN, SupplierCapability.ORDER_CREATE))
                .isFalse();
        assertThat(resolver.isConfigured(new SupplierRef("unknown"), SupplierCapability.STOCK_INQUIRY))
                .isFalse();

        profile.setEnabled(false);
        profileRepository.saveAndFlush(profile);
        assertThat(resolver.isConfigured(MICHELIN, SupplierCapability.STOCK_INQUIRY))
                .isFalse();
    }

    // ── resolveBinding ──────────────────────────────────────────────────────────────

    @Test
    void resolveBindingReturnsBindingAuthConfigAndRegistryTriple() {
        SupplierProfileResolver.ResolvedBinding resolved =
                resolver.resolveBinding(MICHELIN, SupplierCapability.STOCK_INQUIRY);

        assertThat(resolved.profile().getVendorProfileId()).isEqualTo(vendorProfileId());
        assertThat(resolved.binding().getBaseUrl()).isEqualTo("https://api.michelin.example");
        assertThat(resolved.authConfig().getName()).isEqualTo("ediwheel-basic");
        assertThat(resolved.authConfig().getType()).isEqualTo(SupplierAuthType.BASIC_PLUS_APIKEY);
        assertThat(resolved.capability()).isEqualTo(SupplierCapability.STOCK_INQUIRY);
        assertThat(resolved.family()).isEqualTo(ProtocolFamily.EDIWHEEL_A25);
        assertThat(resolved.version().value()).isEqualTo("A2_5");
    }

    @Test
    void unknownSupplierFailsTyped() {
        assertConfigFailure(
                () -> resolver.resolveBinding(new SupplierRef("unknown"), SupplierCapability.STOCK_INQUIRY),
                SupplierConfigurationException.UNKNOWN_SUPPLIER);
        assertConfigFailure(
                () -> resolver.resolvePartyContext(new SupplierRef("unknown"), null),
                SupplierConfigurationException.UNKNOWN_SUPPLIER);
    }

    @Test
    void disabledProfileFailsTyped() {
        profile.setEnabled(false);
        profileRepository.saveAndFlush(profile);

        assertConfigFailure(
                () -> resolver.resolveBinding(MICHELIN, SupplierCapability.STOCK_INQUIRY),
                SupplierConfigurationException.PROFILE_DISABLED);
        assertConfigFailure(
                () -> resolver.resolvePartyContext(MICHELIN, LOCATION_A),
                SupplierConfigurationException.PROFILE_DISABLED);
    }

    @Test
    void absentBindingIsCapabilityNotConfigured() {
        assertConfigFailure(
                () -> resolver.resolveBinding(MICHELIN, SupplierCapability.ORDER_CREATE),
                SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED);
    }

    @Test
    void disabledBindingIsCapabilityNotConfigured() {
        SupplierEndpointBindingEntity binding = bindingRepository
                .findByVendorProfileIdAndCapability(vendorProfileId(), SupplierCapability.STOCK_INQUIRY)
                .orElseThrow();
        binding.setEnabled(false);
        bindingRepository.saveAndFlush(binding);

        assertConfigFailure(
                () -> resolver.resolveBinding(MICHELIN, SupplierCapability.STOCK_INQUIRY),
                SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED);
    }

    @Test
    void bindingNamingMissingAuthConfigFailsTyped() {
        SupplierAuthConfigEntity authConfig = authConfigRepository
                .findByVendorProfileIdAndName(vendorProfileId(), "ediwheel-basic")
                .orElseThrow();
        authConfigRepository.delete(authConfig);
        authConfigRepository.flush();

        assertConfigFailure(
                () -> resolver.resolveBinding(MICHELIN, SupplierCapability.STOCK_INQUIRY),
                SupplierConfigurationException.AUTH_CONFIG_MISSING);
    }

    // ── resolvePartyContext ─────────────────────────────────────────────────────────

    @Test
    void resolvesBillingAloneForAccountLevelReads() {
        SupplierProfileResolver.ResolvedPartyAccounts accounts = resolver.resolvePartyContext(MICHELIN, null);

        assertThat(accounts.billing().getRole()).isEqualTo(SupplierAccountRole.BILLING);
        assertThat(accounts.billing().getAccountNumber()).isEqualTo("0000012345");
        assertThat(accounts.delivery()).isNull();
    }

    @Test
    void resolvesBillingAndDeliveryForLocationBearingExchanges() {
        SupplierProfileResolver.ResolvedPartyAccounts accounts = resolver.resolvePartyContext(MICHELIN, LOCATION_A);

        assertThat(accounts.billing().getAccountNumber()).isEqualTo("0000012345");
        assertThat(accounts.delivery()).isNotNull();
        assertThat(accounts.delivery().getAccountNumber()).isEqualTo("0000067890");
        assertThat(accounts.delivery().getDeliveryLocationId()).isEqualTo(LOCATION_A);
    }

    @Test
    void resolvesDeliveryPerLocationAmongMultipleMappings() {
        // ADR-0050 §5: one billing account plus one delivery account PER location — each
        // location-bearing exchange must get exactly its own location's mapping.
        UUID locationB = UUID.fromString("018f0000-0000-7000-8000-0000000000b2");
        accountRepository.save(
                SupplierProfilePersistenceFixtures.deliveryAccount(vendorProfileId(), locationB, "0000067891"));

        SupplierProfileResolver.ResolvedPartyAccounts forA = resolver.resolvePartyContext(MICHELIN, LOCATION_A);
        SupplierProfileResolver.ResolvedPartyAccounts forB = resolver.resolvePartyContext(MICHELIN, locationB);

        assertThat(forA.delivery().getDeliveryLocationId()).isEqualTo(LOCATION_A);
        assertThat(forA.delivery().getAccountNumber()).isEqualTo("0000067890");
        assertThat(forB.delivery().getDeliveryLocationId()).isEqualTo(locationB);
        assertThat(forB.delivery().getAccountNumber()).isEqualTo("0000067891");
        assertThat(forA.billing().getAccountNumber()).isEqualTo("0000012345");
        assertThat(forB.billing().getAccountNumber()).isEqualTo("0000012345");

        // A location outside the mapping set still fails typed, not with a wrong account.
        assertConfigFailure(
                () -> resolver.resolvePartyContext(MICHELIN, LOCATION_UNMAPPED),
                SupplierConfigurationException.MISSING_DELIVERY_MAPPING);
    }

    @Test
    void missingBillingAccountFailsTyped() {
        accountRepository.deleteAll(
                accountRepository.findByVendorProfileIdAndRole(vendorProfileId(), SupplierAccountRole.BILLING));
        accountRepository.flush();

        assertConfigFailure(
                () -> resolver.resolvePartyContext(MICHELIN, LOCATION_A),
                SupplierConfigurationException.MISSING_BILLING_ACCOUNT);
    }

    @Test
    void missingDeliveryMappingForRequestedLocationFailsTyped() {
        assertConfigFailure(
                () -> resolver.resolvePartyContext(MICHELIN, LOCATION_UNMAPPED),
                SupplierConfigurationException.MISSING_DELIVERY_MAPPING);
    }

    private static void assertConfigFailure(ThrowingCallable callable, String expectedCode) {
        assertThatThrownBy(callable)
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }
}
