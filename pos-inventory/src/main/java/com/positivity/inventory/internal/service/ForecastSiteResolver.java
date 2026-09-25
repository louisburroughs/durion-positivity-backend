package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
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
 *
 * <p>That normalisation is not specific to forecasting, and this is the module's one
 * implementation of it: {@link StagingLocationResolver} uses it too, to find the site whose
 * declared staging location applies to a receipt or a purchase order's ship-to (#2009).
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

    /**
     * Batch form of {@link #resolveForecastSite(UUID)} for a page of rows (#2206): one {@code IN}
     * query instead of one round trip per row. Every distinct, non-null input id is present in
     * the result, mapped to its site (or to itself when it is not a bin, or the bin carries no
     * site).
     */
    public @NonNull Map<UUID, UUID> resolveAll(@Nullable Collection<UUID> locationOrStorageLocationIds) {
        // A plain HashMap, never Map.of(): callers key lookups off a task's raw location field,
        // which a test fixture (schema says non-null, but nothing enforces that off a real
        // datasource) may leave null — Map.of() throws NPE on a null-key get(), a HashMap just
        // answers null, exactly like "not resolved".
        if (locationOrStorageLocationIds == null || locationOrStorageLocationIds.isEmpty()) {
            return new HashMap<>();
        }
        List<UUID> distinctIds = locationOrStorageLocationIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return new HashMap<>();
        }
        Map<UUID, ExtStorageLocationReplica> byId = storageLocationReplicaRepository.findAllById(distinctIds).stream()
                .collect(java.util.stream.Collectors.toMap(ExtStorageLocationReplica::getStorageLocationId, r -> r));
        Map<UUID, UUID> resolved = new HashMap<>();
        for (UUID id : distinctIds) {
            ExtStorageLocationReplica replica = byId.get(id);
            resolved.put(id, replica != null && replica.getSiteId() != null ? replica.getSiteId() : id);
        }
        return resolved;
    }
}
