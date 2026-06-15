package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.rollup.SiteInventoryRollupResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Rolls up on-hand and outstanding-allocation quantities along the
 * storage-location hierarchy of a site (CAP-218 #658).
 */
public interface SiteInventoryRollupService {

    /**
     * Computes the rollup tree for a site.
     *
     * @param siteId site (Location) identifier; resolved against pos-location
     * @param sku optional stock item filter; blank treated as absent
     * @param depth optional maximum tree depth to return (1 = roots only); null = full tree
     * @param includeEmpty when false (default), nodes whose rolled-up quantities are all zero are pruned
     */
    @NonNull
    SiteInventoryRollupResponse getSiteInventoryRollup(
            @NonNull UUID siteId, @Nullable String sku, @Nullable Integer depth, boolean includeEmpty);
}
