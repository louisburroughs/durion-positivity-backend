package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.SecurityContextHelper;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * HTTP-boundary-only location-scope enforcement for pick lists (ADR-0061 §3, #2204).
 *
 * <p><b>Why this is not in {@code PickListServiceImpl}</b> (PR #2227 review item 1, BLOCKER):
 * {@code InventoryCommandListener} calls {@code PickListService.releasePickList} and {@code
 * confirmPickTask} directly for Kafka-driven commands (async release/confirm requested by
 * pos-workorder, #901) with no authenticated {@code SecurityContext} at all — there is no caller
 * to scope. {@link SecurityContextHelper#locationScope()} throws when the security context holds
 * no authentication, so a scope check inside the service broke every command-driven pick-list
 * update. This class is called only from {@code PickListController}; the service stays
 * scope-free so both the HTTP path and the command path keep working.
 *
 * <p>A pick list carries no location of its own; its site(s) are derived from its tasks'
 * suggested locations. Sourcing can pick a different candidate location per task (odoo-parity
 * H1/H2), so a list can legitimately span sites — every distinct resolved site is checked, not
 * just the first (PR #2227 review item 2). A list with no tasks, or whose tasks resolve no site
 * at all, is not gated — there is nothing to check against, same as before #2204 existed.
 */
@Component
@RequiredArgsConstructor
public class PickListLocationScopeGuard {

    private final PickTaskRepository pickTaskRepository;
    private final ForecastSiteResolver forecastSiteResolver;

    /** The pick list's distinct resolved sites; empty when it has no tasks or none resolve a site. */
    public @NonNull Set<UUID> resolveSites(@NonNull UUID pickListId) {
        Set<UUID> sites = new LinkedHashSet<>();
        for (PickTaskEntity task : pickTaskRepository.findByPickList_PickListId(pickListId)) {
            UUID suggested = task.getSuggestedLocationId();
            if (suggested == null) {
                continue;
            }
            UUID site = forecastSiteResolver.resolveForecastSite(suggested);
            if (site != null) {
                sites.add(site);
            }
        }
        return sites;
    }

    /**
     * Gates every distinct resolved site of the pick list against {@code permission} (403 {@code
     * LOCATION_SCOPE_DENIED} on the first one out of reach). A list with no resolvable site is
     * not gated.
     */
    public void require(@NonNull UUID pickListId, @NonNull String permission) {
        for (UUID site : resolveSites(pickListId)) {
            SecurityContextHelper.locationScope().require(permission, site);
        }
    }

    /**
     * Gates one already-known location (e.g. {@code confirmPickTask}'s scanned location, PR
     * #2227 review item 3), resolved to its site first. A location that resolves no site is not
     * gated.
     */
    public void requireForLocation(@NonNull UUID locationId, @NonNull String permission) {
        UUID site = forecastSiteResolver.resolveForecastSite(locationId);
        if (site != null) {
            SecurityContextHelper.locationScope().require(permission, site);
        }
    }

    /**
     * Whether every distinct resolved site of the pick list is within the caller's reach for
     * {@code permission} — the non-throwing form for narrowing a list rather than gating one id
     * (PR #2227 review item 4). A list with no resolvable site is always within reach.
     */
    public boolean isWithinReach(@NonNull UUID pickListId, @NonNull String permission) {
        LocationScope scope = SecurityContextHelper.locationScope();
        for (UUID site : resolveSites(pickListId)) {
            if (!scope.covers(permission, site)) {
                return false;
            }
        }
        return true;
    }
}
