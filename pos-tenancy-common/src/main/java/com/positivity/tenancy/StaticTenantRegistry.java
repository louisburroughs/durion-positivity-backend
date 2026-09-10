package com.positivity.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * Transitional {@link TenantRegistry}: {@code pos.tenancy.tenants}, falling back to the single
 * default tenant. Replaced by the {@code ext_tenant}-backed registry in plan WS2a.
 */
public class StaticTenantRegistry implements TenantRegistry {

    private final List<UUID> tenants;

    public StaticTenantRegistry(TenancyProperties properties) {
        List<UUID> configured = properties.getTenants();
        this.tenants = !configured.isEmpty()
                ? List.copyOf(configured)
                : properties.getDefaultTenantId().map(List::of).orElse(List.of());
    }

    @Override
    public List<UUID> activeTenantIds() {
        return tenants;
    }
}
