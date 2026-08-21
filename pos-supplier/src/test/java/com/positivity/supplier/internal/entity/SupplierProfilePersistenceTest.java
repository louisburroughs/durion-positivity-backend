package com.positivity.supplier.internal.entity;

import static com.positivity.supplier.internal.entity.SupplierProfilePersistenceFixtures.basicAuth;
import static com.positivity.supplier.internal.entity.SupplierProfilePersistenceFixtures.billingAccount;
import static com.positivity.supplier.internal.entity.SupplierProfilePersistenceFixtures.binding;
import static com.positivity.supplier.internal.entity.SupplierProfilePersistenceFixtures.deliveryAccount;
import static com.positivity.supplier.internal.entity.SupplierProfilePersistenceFixtures.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.repository.SupplierAccountRepository;
import com.positivity.supplier.internal.repository.SupplierAuthConfigRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Boots the Flyway migrations (V1 + {@code V2__supplier_vendor_profiles.sql}) against H2 in
 * PostgreSQL mode with {@code ddl-auto=validate}, proving the JPA mappings match the DDL,
 * then exercises CRUD, the ADR-0018/0024 audit fields, and every ADR-0050 constraint: unique
 * {@code supplierRef}, unique auth name per profile, one BILLING per profile, one DELIVERY
 * per (profile, location), the role/location CHECK + entity invariant, and one binding per
 * (profile, capability).
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_entities;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestClockConfig.class})
class SupplierProfilePersistenceTest {

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

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsFullProfileAggregateWithUuidV7IdsAndAuditFields() {
        SupplierProfileEntity profile = profileRepository.saveAndFlush(profile("michelin-eu"));

        assertThat(profile.getVendorProfileId()).isNotNull();
        assertThat(profile.getVendorProfileId().version()).isEqualTo(7);
        assertThat(profile.getCreatedAt()).isNotNull();
        assertThat(profile.getUpdatedAt()).isNotNull();
        // No security context in the JPA slice: the ADR-0018 auditor falls back to "system".
        assertThat(profile.getCreatedBy()).isEqualTo("system");
        assertThat(profile.getUpdatedBy()).isEqualTo("system");
        assertThat(profile.getVersion()).isZero();

        SupplierAuthConfigEntity auth =
                authConfigRepository.saveAndFlush(basicAuth(profile.getVendorProfileId(), "ediwheel-basic"));
        SupplierAccountEntity billing =
                accountRepository.saveAndFlush(billingAccount(profile.getVendorProfileId(), "0000012345"));
        SupplierAccountEntity delivery =
                accountRepository.saveAndFlush(deliveryAccount(profile.getVendorProfileId(), LOCATION_A, "0000067890"));
        SupplierEndpointBindingEntity binding = bindingRepository.saveAndFlush(
                binding(profile.getVendorProfileId(), SupplierCapability.STOCK_INQUIRY, "ediwheel-basic"));

        assertThat(auth.getId().version()).isEqualTo(7);
        assertThat(billing.getCreatedBy()).isEqualTo("system");
        assertThat(delivery.getDeliveryLocationId()).isEqualTo(LOCATION_A);
        assertThat(binding.getProtocolVersion()).isEqualTo("A2_5");
        assertThat(bindingRepository.existsByVendorProfileIdAndAuthConfigName(
                        profile.getVendorProfileId(), "ediwheel-basic"))
                .isTrue();
    }

    @Test
    void supplierRefIsUnique() {
        profileRepository.saveAndFlush(profile("michelin-eu"));

        assertThatThrownBy(() -> profileRepository.saveAndFlush(profile("michelin-eu")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void authConfigNameIsUniquePerProfileButReusableAcrossProfiles() {
        SupplierProfileEntity first = profileRepository.saveAndFlush(profile("michelin-eu"));
        SupplierProfileEntity second = profileRepository.saveAndFlush(profile("michelin-na"));
        authConfigRepository.saveAndFlush(basicAuth(first.getVendorProfileId(), "ediwheel-basic"));

        // Same name on another profile is legal.
        authConfigRepository.saveAndFlush(basicAuth(second.getVendorProfileId(), "ediwheel-basic"));

        assertThatThrownBy(() ->
                        authConfigRepository.saveAndFlush(basicAuth(first.getVendorProfileId(), "ediwheel-basic")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneBillingAccountPerProfile() {
        SupplierProfileEntity profile = profileRepository.saveAndFlush(profile("michelin-eu"));
        accountRepository.saveAndFlush(billingAccount(profile.getVendorProfileId(), "0000012345"));

        assertThatThrownBy(() ->
                        accountRepository.saveAndFlush(billingAccount(profile.getVendorProfileId(), "0000099999")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneDeliveryAccountPerProfileAndLocation() {
        SupplierProfileEntity profile = profileRepository.saveAndFlush(profile("michelin-eu"));
        accountRepository.saveAndFlush(deliveryAccount(profile.getVendorProfileId(), LOCATION_A, "0000067890"));

        // A second location is legal; the same location is not.
        accountRepository.saveAndFlush(deliveryAccount(profile.getVendorProfileId(), LOCATION_B, "0000067891"));

        assertThatThrownBy(() -> accountRepository.saveAndFlush(
                        deliveryAccount(profile.getVendorProfileId(), LOCATION_A, "0000067892")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void entityInvariantRejectsDeliveryWithoutLocationAndBillingWithLocation() {
        SupplierProfileEntity profile = profileRepository.saveAndFlush(profile("michelin-eu"));

        SupplierAccountEntity deliveryWithoutLocation = deliveryAccount(profile.getVendorProfileId(), LOCATION_A, "1");
        deliveryWithoutLocation.setDeliveryLocationId(null);
        Throwable deliveryFailure = catchThrowable(() -> accountRepository.saveAndFlush(deliveryWithoutLocation));
        assertThat(rootCause(deliveryFailure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deliveryLocationId");

        SupplierAccountEntity billingWithLocation = billingAccount(profile.getVendorProfileId(), "2");
        billingWithLocation.setDeliveryLocationId(LOCATION_A);
        Throwable billingFailure = catchThrowable(() -> accountRepository.saveAndFlush(billingWithLocation));
        assertThat(rootCause(billingFailure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BILLING");
    }

    @Test
    void databaseCheckBacksTheRoleLocationInvariant() {
        SupplierProfileEntity profile = profileRepository.saveAndFlush(profile("michelin-eu"));

        // Bypass the entity to prove the DB CHECK holds on raw SQL too.
        Throwable thrown = catchThrowable(() -> entityManager
                .createNativeQuery("INSERT INTO supplier_account (id, vendor_profile_id, role,"
                        + " account_number, delivery_location_id, created_at, updated_at, version)"
                        + " VALUES (?1, ?2, 'DELIVERY', 'X', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, profile.getVendorProfileId())
                .executeUpdate());
        assertThat(thrown).isNotNull();
        assertThat(rootCause(thrown).getMessage()).containsIgnoringCase("chk_saccount_role_location");
    }

    private static Throwable rootCause(Throwable thrown) {
        assertThat(thrown).isNotNull();
        Throwable current = thrown;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    @Test
    void atMostOneBindingPerProfileAndCapability() {
        SupplierProfileEntity profile = profileRepository.saveAndFlush(profile("michelin-eu"));
        authConfigRepository.saveAndFlush(basicAuth(profile.getVendorProfileId(), "ediwheel-basic"));
        bindingRepository.saveAndFlush(
                binding(profile.getVendorProfileId(), SupplierCapability.STOCK_INQUIRY, "ediwheel-basic"));

        // Another capability is legal; the same capability is not.
        bindingRepository.saveAndFlush(
                binding(profile.getVendorProfileId(), SupplierCapability.ORDER_CREATE, "ediwheel-basic"));

        assertThatThrownBy(() -> bindingRepository.saveAndFlush(
                        binding(profile.getVendorProfileId(), SupplierCapability.STOCK_INQUIRY, "ediwheel-basic")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
