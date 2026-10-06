package com.positivity.supplier.internal.entity;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import com.positivity.supplier.internal.enums.RetryBackoff;
import com.positivity.supplier.internal.enums.SupplierAccountRole;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.repository.SupplierVendorRepository;
import java.util.UUID;

/** Shared entity fixtures for the ADR-0050 vendor profile persistence/service tests. */
public final class SupplierProfilePersistenceFixtures {

    private SupplierProfilePersistenceFixtures() {
        // fixtures
    }

    /**
     * Persists an ACTIVE vendor of the bound tenant and returns its id: every profile belongs to one
     * (#2516, {@code supplier_profile.vendor_id NOT NULL}).
     */
    public static UUID vendor(SupplierVendorRepository vendorRepository) {
        String number = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        return vendorRepository
                .saveAndFlush(SupplierVendorEntity.builder()
                        .vendorNumber(number)
                        .legalName("Vendor " + number)
                        .displayName("Vendor " + number)
                        .defaultPaymentTerms("NET30")
                        .defaultCurrency("EUR")
                        .status(com.positivity.supplier.internal.enums.VendorStatus.ACTIVE)
                        .build())
                .getVendorId();
    }

    public static SupplierProfileEntity profile(String supplierRef, UUID vendorId) {
        SupplierProfileEntity profile = profile(supplierRef);
        profile.setVendorId(vendorId);
        return profile;
    }

    public static SupplierProfileEntity profile(String supplierRef) {
        return SupplierProfileEntity.builder()
                .supplierRef(supplierRef)
                .displayName("Michelin Europe")
                .enabled(true)
                .sandbox(false)
                .connectTimeoutMs(5000)
                .readTimeoutMs(30000)
                .retryMaxAttempts(3)
                .retryBackoff(RetryBackoff.EXPONENTIAL)
                .sourceOfTruth(ProfileSourceOfTruth.ADMIN)
                .build();
    }

    public static SupplierAuthConfigEntity basicAuth(UUID vendorProfileId, String name) {
        return SupplierAuthConfigEntity.builder()
                .vendorProfileId(vendorProfileId)
                .name(name)
                .type(SupplierAuthType.BASIC_PLUS_APIKEY)
                .usernameRef("env:MICHELIN_EDI_USER")
                .passwordRef("env:MICHELIN_EDI_PASSWORD")
                .apiKeyRef("env:MICHELIN_EDI_APIKEY")
                .apiKeyHeader("apikey")
                .build();
    }

    public static SupplierAccountEntity billingAccount(UUID vendorProfileId, String accountNumber) {
        return SupplierAccountEntity.builder()
                .vendorProfileId(vendorProfileId)
                .role(SupplierAccountRole.BILLING)
                .accountNumber(accountNumber)
                .agencyCode("31")
                .build();
    }

    public static SupplierAccountEntity deliveryAccount(UUID vendorProfileId, UUID locationId, String accountNumber) {
        return SupplierAccountEntity.builder()
                .vendorProfileId(vendorProfileId)
                .role(SupplierAccountRole.DELIVERY)
                .accountNumber(accountNumber)
                .agencyCode("31")
                .deliveryLocationId(locationId)
                .build();
    }

    public static SupplierEndpointBindingEntity binding(
            UUID vendorProfileId, SupplierCapability capability, String authConfigName) {
        return SupplierEndpointBindingEntity.builder()
                .vendorProfileId(vendorProfileId)
                .capability(capability)
                .protocolFamily(ProtocolFamily.EDIWHEEL_A25)
                .protocolVersion("A2_5")
                .baseUrl("https://api.michelin.example")
                .path("/A2_5/inquiry")
                .authConfigName(authConfigName)
                .enabled(true)
                .build();
    }
}
