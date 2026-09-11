package com.positivity.tenancy;

/**
 * Capability of a {@link TenantRegistry} that can answer whether its current tenant list is a
 * complete picture of the fleet or a fallback (ADR-0062 plan WS6).
 *
 * <p>A registry that fetches its list from elsewhere ({@link RemoteTenantRegistry}) starts on a
 * static seed and keeps its last good snapshot through an outage, so "the registry returned some
 * tenants" is not the same as "these are all the tenants". Per-tenant work is still worth running
 * over an incomplete list — tuning the tenants we know beats tuning none. Fleet-wide work is not:
 * a rollup over part of the fleet written as if it covered all of it is wrong, not merely late.
 * Such a job asks here before its fleet-wide step.
 *
 * <p>A registry whose list is authoritative by construction ({@link StaticTenantRegistry}, or a
 * module-owned one backed by a local replica) does not implement this; callers treat its absence as
 * complete. {@link TenantIterator#hasCompleteTenantList()} is the check, so no caller needs the
 * {@code instanceof}.
 */
public interface TenantRegistryFreshness {

    /**
     * Whether {@link TenantRegistry#activeTenantIds()} currently lists every active tenant.
     *
     * @return {@code false} while the registry has never fetched successfully, or while its most
     *     recent refresh failed and it is answering from an older snapshot
     */
    boolean hasCompleteSnapshot();
}
