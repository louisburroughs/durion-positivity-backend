package com.positivity.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * The tenants a module knows about, for per-tenant scheduled work ({@link TenantIterator}).
 *
 * <p>Backed by {@link StaticTenantRegistry} ({@code pos.tenancy.tenants}, else the default tenant)
 * until a module sets {@code pos.tenancy.registry.mode=REMOTE}, which swaps in {@link
 * RemoteTenantRegistry}: a cached lookup against {@code pos-tenant} (plan WS4-2). A module that
 * keeps its own {@code ext_tenant} replica ({@code pos-security-service}) declares its own bean
 * instead; the auto-configured one backs off.
 */
public interface TenantRegistry {

    /** Ids of every tenant that is {@code ACTIVE}, in a stable order. */
    List<UUID> activeTenantIds();
}
