package com.positivity.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * Static {@link TenantRegistry}: {@code pos.tenancy.tenants}, falling back to the single default
 * tenant. The default ({@code pos.tenancy.registry.mode=STATIC}) and the snapshot a {@link
 * RemoteTenantRegistry} starts from.
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
