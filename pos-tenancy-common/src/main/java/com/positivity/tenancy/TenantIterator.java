package com.positivity.tenancy;

import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs per-tenant scheduled work once per active tenant with that tenant bound (ADR-0062 §3).
 *
 * <p>A failure for one tenant is logged and does not stop the others: a per-tenant job that throws
 * for tenant A must still run for tenant B, or one bad tenant silently starves the fleet. The
 * per-tenant count is the observability hook the plan asks for: a job that visits zero tenants logs
 * at WARN so a misconfigured registry is visible.
 */
public class TenantIterator {

    private static final Logger log = LoggerFactory.getLogger(TenantIterator.class);

    private final TenantRegistry registry;

    public TenantIterator(TenantRegistry registry) {
        this.registry = registry;
    }

    /**
     * Whether the registry's current list names every active tenant.
     *
     * <p>Per-tenant work runs regardless — an incomplete list still deserves the tenants it does
     * name. A job that then rolls the sweep up into one fleet-wide write asks this first: over a
     * partial list that write is wrong rather than merely late. Registries that are authoritative by
     * construction do not implement {@link TenantRegistryFreshness} and answer {@code true} here.
     *
     * @return {@code false} only when the registry declares its current snapshot incomplete
     */
    public boolean hasCompleteTenantList() {
        return !(registry instanceof TenantRegistryFreshness freshness) || freshness.hasCompleteSnapshot();
    }

    /**
     * Invoke {@code work} for every active tenant, each with its tenant bound.
     *
     * @return the number of tenants for which {@code work} completed without throwing
     */
    public int forEachActiveTenant(Consumer<UUID> work) {
        var tenants = registry.activeTenantIds();
        if (tenants.isEmpty()) {
            log.warn("TenantIterator visited no tenants: the registry is empty");
            return 0;
        }
        int completed = 0;
        for (UUID tenantId : tenants) {
            try {
                TenantContext.runAs(tenantId, () -> work.accept(tenantId));
                completed++;
            } catch (RuntimeException e) {
                log.error("Per-tenant work failed for tenant {}; continuing with the next tenant", tenantId, e);
            }
        }
        return completed;
    }
}
