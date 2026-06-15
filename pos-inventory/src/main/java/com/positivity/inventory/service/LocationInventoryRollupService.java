package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.rollup.LocationInventoryRollupResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Rolls inventory totals up beyond a single site to a parent Location
 * (building/place/region) along a typed parent chain (CAP-218 #659).
 */
public interface LocationInventoryRollupService {

    /**
     * Computes per-site rollup summaries plus a grand total for all descendant
     * sites of {@code locationId} along the {@code parentType} chain.
     *
     * @param locationId parent Location identifier (not a storage location)
     * @param parentType pos-location parent chain type; null defaults to PHYSICAL
     * @param sku optional stock item filter; blank treated as absent
     * @param expandTree when true, each site entry carries its full rollup tree;
     *        rejected when the descendant site count exceeds the configured cap
     * @param depth optional per-site tree depth (only meaningful with expandTree)
     * @param includeEmpty when false, all-zero tree nodes are pruned (only
     *        meaningful with expandTree); site summaries are always returned
     */
    @NonNull
    LocationInventoryRollupResponse getLocationInventoryRollup(
            @NonNull UUID locationId,
            @Nullable String parentType,
            @Nullable String sku,
            boolean expandTree,
            @Nullable Integer depth,
            boolean includeEmpty);
}
