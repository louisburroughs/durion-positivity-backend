package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.service.StorageLocationTopologyService;
import com.positivity.inventory.internal.service.StorageLocationTopologyService.LocationDescendant;
import com.positivity.inventory.internal.dto.rollup.LocationInventoryRollupResponse;
import com.positivity.inventory.internal.dto.rollup.RollupQuantities;
import com.positivity.inventory.internal.dto.rollup.SiteInventoryRollupResponse;
import com.positivity.inventory.internal.dto.rollup.SiteRollupSummary;
import com.positivity.inventory.internal.exception.RollupExpansionTooLargeException;
import com.positivity.inventory.service.LocationInventoryRollupService;
import com.positivity.inventory.service.SiteInventoryRollupService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Parent-location rollup (CAP-218 #659): resolves descendant locations via
 * pos-location's typed-parent descendants contract, computes the site rollup
 * per descendant, and aggregates a grand total. Descendants with no storage
 * locations contribute zero totals.
 *
 * <p>{@code expand=tree} fan-out is capped: every expanded site costs one
 * topology fetch plus grouped ledger queries, so over-cap requests are
 * rejected with 422 rather than degrading the service.
 */
@Service
public class LocationInventoryRollupServiceImpl implements LocationInventoryRollupService {

    private static final String DEFAULT_PARENT_TYPE = "PHYSICAL";

    /**
     * Consumer-side pin of pos-location's {@code ParentType} value set
     * (ADR-0016). Kept in sync via the CAP-214 contract guide.
     */
    private static final Set<String> VALID_PARENT_TYPES = Set.of(
            "HOME_OFFICE", "HEADQUARTERS", "REGION", "DISTRICT", "PHYSICAL", "ORGANIZATIONAL", "FINANCIAL", "SHIPPING");

    private final StorageLocationTopologyService topologyService;
    private final SiteInventoryRollupService siteInventoryRollupService;
    private final int expandSiteCap;

    public LocationInventoryRollupServiceImpl(
            StorageLocationTopologyService topologyService,
            SiteInventoryRollupService siteInventoryRollupService,
            @Value("${pos.inventory.rollup.expand-site-cap:25}") int expandSiteCap) {
        this.topologyService = topologyService;
        this.siteInventoryRollupService = siteInventoryRollupService;
        this.expandSiteCap = expandSiteCap;
    }

    @Override
    @NonNull
    public LocationInventoryRollupResponse getLocationInventoryRollup(
            @NonNull UUID locationId,
            @Nullable String parentType,
            @Nullable String sku,
            boolean expandTree,
            @Nullable Integer depth,
            boolean includeEmpty) {
        String resolvedParentType = resolveParentType(parentType);

        List<LocationDescendant> descendants = topologyService.fetchDescendants(locationId, resolvedParentType);

        if (expandTree && descendants.size() > expandSiteCap) {
            throw new RollupExpansionTooLargeException(descendants.size(), expandSiteCap);
        }

        List<SiteRollupSummary> sites = new ArrayList<>(descendants.size());
        RollupQuantities grandTotal = RollupQuantities.ZERO;
        for (LocationDescendant descendant : descendants) {
            SiteInventoryRollupResponse siteRollup =
                    siteInventoryRollupService.getSiteInventoryRollup(descendant.id(), sku, depth, includeEmpty);
            grandTotal = grandTotal.plus(siteRollup.totals());
            sites.add(new SiteRollupSummary(
                    descendant.id(), descendant.name(), siteRollup.totals(), expandTree ? siteRollup.nodes() : null));
        }

        return new LocationInventoryRollupResponse(locationId, resolvedParentType, grandTotal, List.copyOf(sites));
    }

    private String resolveParentType(@Nullable String parentType) {
        if (parentType == null || parentType.isBlank()) {
            return DEFAULT_PARENT_TYPE;
        }
        String normalized = parentType.trim().toUpperCase(Locale.ROOT);
        if (!VALID_PARENT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Unknown parentType '" + parentType + "'; expected one of " + VALID_PARENT_TYPES);
        }
        return normalized;
    }
}
