package com.positivity.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * Static {@link TenantRegistry}: {@code pos.tenancy.tenants}, falling back to the single default
 * tenant. The default ({@code pos.tenancy.registry.mode=STATIC}) and the snapshot a {@link
 * RemoteTenantRegistry} starts from.
 *
 * <p>Filters {@link PlatformTenant#ID} out of both sources ({@link TenantRegistry#activeTenantIds()}
 * forbids it): {@code pos-tenant} sets {@code pos.tenancy.default-tenant-id} to the platform tenant
 * for its own rows, and any module's {@code pos.tenancy.tenants} could list it too, but neither may
 * hand it to a {@link TenantIterator} sweep.
 */
public class StaticTenantRegistry implements TenantRegistry {

    private final List<UUID> tenants;

    public StaticTenantRegistry(TenancyProperties properties) {
        List<UUID> configured = properties.getTenants();
        List<UUID> unfiltered = !configured.isEmpty()
                ? configured
                : properties.getDefaultTenantId().map(List::of).orElse(List.of());
        this.tenants = unfiltered.stream()
                .filter(tenantId -> !PlatformTenant.ID.equals(tenantId))
                .toList();
    }

    @Override
    public List<UUID> activeTenantIds() {
        return tenants;
    }
}
