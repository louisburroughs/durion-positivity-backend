package com.positivity.supplier.internal.entity;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import com.positivity.supplier.internal.enums.RetryBackoff;
import com.positivity.supplier.internal.enums.SupplierAccountRole;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import java.util.UUID;

/** Shared entity fixtures for the ADR-0050 vendor profile persistence/service tests. */
public final class SupplierProfilePersistenceFixtures {

    private SupplierProfilePersistenceFixtures() {
        // fixtures
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
