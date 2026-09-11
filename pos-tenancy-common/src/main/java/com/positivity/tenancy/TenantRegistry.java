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

    /**
     * The active tenant list together with whether it is a complete picture of the fleet, read as
     * one atomic pair.
     *
     * <p>A caller that needs both values — a fleet-wide rollup grading itself against the exact
     * list it iterated, for instance — must not call {@link #activeTenantIds()} and, separately,
     * {@link TenantRegistryFreshness#hasCompleteSnapshot()}: on a registry whose snapshot changes
     * concurrently (a background refresh landing between the two calls) that composes a list from
     * one moment with a completeness verdict from another, and the torn pair can read as complete
     * while missing tenants the refresh just added. This method exists so there is always one call
     * that cannot be torn.
     *
     * <p>The default below composes the two separate reads and is adequate only for a registry that
     * cannot be caught mid-refresh — one that is authoritative by construction ({@link
     * StaticTenantRegistry}, or a module-owned registry backed by a local replica), which is exactly
     * the set of registries that do not implement {@link TenantRegistryFreshness} and so always
     * report complete here. Any registry that implements {@link TenantRegistryFreshness} because its
     * snapshot can go stale or be mid-refresh ({@link RemoteTenantRegistry}) MUST override this method
     * to publish the list and its completeness from one consistent internal snapshot instead.
     *
     * @return the current tenant list paired with whether it is complete
     */
    default Snapshot snapshot() {
        List<UUID> tenantIds = activeTenantIds();
        boolean complete = !(this instanceof TenantRegistryFreshness freshness) || freshness.hasCompleteSnapshot();
        return new Snapshot(tenantIds, complete);
    }

    /**
     * One atomic read of {@link #activeTenantIds()} and its completeness verdict, as returned by
     * {@link #snapshot()}.
     */
    record Snapshot(List<UUID> tenantIds, boolean complete) {}
}
