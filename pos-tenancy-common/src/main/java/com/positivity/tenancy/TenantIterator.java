package com.positivity.tenancy;

import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
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
     * Invoke {@code work} for every active tenant, each with its tenant bound.
     *
     * @return the number of tenants for which {@code work} completed without throwing
     */
    public int forEachActiveTenant(Consumer<UUID> work) {
        return sweep(work).completed();
    }

    /**
     * Invoke {@code work} for every active tenant, each with its tenant bound, and report whether
     * the list {@code work} was run over was itself a complete picture of the fleet.
     *
     * <p>For a caller that only needs the per-tenant count, {@link #forEachActiveTenant(Consumer)}
     * is simpler. This overload is for a job that then rolls its per-tenant results up into one
     * fleet-wide write: over a partial list that write is wrong rather than merely late, so the
     * completeness verdict must belong to the exact list the sweep iterated. {@link
     * TenantRegistry#activeTenantIds()} and, when the registry implements {@link
     * TenantRegistryFreshness}, {@link TenantRegistryFreshness#hasCompleteSnapshot()} are therefore
     * read together before {@code work} runs for anyone — not afterward, when a sweep that can run
     * for as long as the per-tenant work takes has given a concurrent refresh (triggered by any
     * other caller sharing the registry, not only this one) time to move the registry from an
     * incomplete snapshot to a complete one, or the reverse, making the verdict belong to a
     * snapshot other than the one just iterated. Registries that are authoritative by construction
     * do not implement {@link TenantRegistryFreshness} and always report complete.
     *
     * @return the per-tenant completion count and whether the iterated list was complete
     */
    public @NonNull Sweep sweep(Consumer<UUID> work) {
        var tenants = registry.activeTenantIds();
        boolean completeTenantList =
                !(registry instanceof TenantRegistryFreshness freshness) || freshness.hasCompleteSnapshot();
        if (tenants.isEmpty()) {
            log.warn("TenantIterator visited no tenants: the registry is empty");
            return new Sweep(0, completeTenantList);
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
        return new Sweep(completed, completeTenantList);
    }

    /**
     * The outcome of one {@link #sweep(Consumer)}: how many tenants finished {@code work} without
     * throwing, and whether {@code activeTenantIds()} was a complete picture of the fleet at the
     * moment this sweep read it.
     */
    public record Sweep(int completed, boolean completeTenantList) {}
}
