package com.positivity.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * The tenants a module knows about, for per-tenant scheduled work. Backed by the module's {@code
 * ext_tenant} replica once {@code pos-tenant} exists (plan WS2a); until then by {@code
 * pos.tenancy.tenants} (or the transitional default tenant) through {@link StaticTenantRegistry}.
 */
public interface TenantRegistry {

    /** Ids of every tenant that is {@code ACTIVE}, in a stable order. */
    List<UUID> activeTenantIds();
}
