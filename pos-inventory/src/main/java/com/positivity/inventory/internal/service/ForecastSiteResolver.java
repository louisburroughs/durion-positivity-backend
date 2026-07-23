package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves a possibly bin-level location id to the SITE scope used by forecast supply
 * queries (odoo-parity A2, issue #1028; extracted for reuse by the replenishment
 * orderpoint math in F2, issue #1040).
 *
 * <p>Forecast supply is keyed by ship-to SITE ({@code PurchaseOrderEntity.shipToLocationId},
 * transfer destination site); summary/scope/policy ids are frequently bin-level. A bin is
 * resolved to its parent site via the storage-location replica; ids not present in the
 * replica are assumed to already be site ids and pass through unchanged.
 */
@Component
@RequiredArgsConstructor
public class ForecastSiteResolver {

    private final ExtStorageLocationReplicaRepository storageLocationReplicaRepository;

    public @Nullable UUID resolveForecastSite(@Nullable UUID locationOrStorageLocationId) {
        if (locationOrStorageLocationId == null) {
            return null;
        }
        return storageLocationReplicaRepository
                .findById(locationOrStorageLocationId)
                .map(ExtStorageLocationReplica::getSiteId)
                .orElse(locationOrStorageLocationId);
    }
}
