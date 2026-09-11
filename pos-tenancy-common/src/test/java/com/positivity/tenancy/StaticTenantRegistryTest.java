package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StaticTenantRegistry} must uphold {@link TenantRegistry#activeTenantIds()}'s contract that
 * the platform tenant never appears in the active-tenant list, the same invariant {@link
 * RemoteTenantRegistry} already enforces on its static seed and every fetched list (Copilot review
 * round 4, #1956).
 */
class StaticTenantRegistryTest {

    private static final UUID ACME = UUID.fromString("01990000-0000-7000-8000-000000000a01");
    private static final UUID BOLT = UUID.fromString("01990000-0000-7000-8000-000000000b02");

    @Test
    @DisplayName("the platform tenant is excluded from pos.tenancy.tenants even when configured explicitly")
    void platformTenantIsExcludedFromTheConfiguredList() {
        TenancyProperties properties = new TenancyProperties();
        // A static deployment could plausibly list the platform tenant alongside ordinary tenants
        // (it is a valid tenant id, just not one a per-tenant sweep may visit).
        properties.setTenants(List.of(PlatformTenant.ID, ACME, BOLT));

        assertThat(new StaticTenantRegistry(properties).activeTenantIds()).containsExactly(ACME, BOLT);
    }

    @Test
    @DisplayName("the platform tenant alone as the configured list yields an empty registry, not a one-tenant sweep")
    void platformTenantAloneInTheConfiguredListYieldsNoTenants() {
        TenancyProperties properties = new TenancyProperties();
        properties.setTenants(List.of(PlatformTenant.ID));

        assertThat(new StaticTenantRegistry(properties).activeTenantIds()).isEmpty();
    }

    @Test
    @DisplayName("the platform tenant as pos.tenancy.default-tenant-id yields no tenants, not the platform tenant")
    void platformTenantAsDefaultTenantIdYieldsNoTenants() {
        TenancyProperties properties = new TenancyProperties();
        // pos-tenant sets exactly this: its own rows all belong to the platform tenant.
        properties.setDefaultTenantId(PlatformTenant.ID);

        assertThat(new StaticTenantRegistry(properties).activeTenantIds()).isEmpty();
    }

    @Test
    @DisplayName("an ordinary default tenant still comes through unfiltered")
    void ordinaryDefaultTenantIdIsUnaffected() {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(ACME);

        assertThat(new StaticTenantRegistry(properties).activeTenantIds()).containsExactly(ACME);
    }
}
