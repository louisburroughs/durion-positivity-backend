package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.LeadTimeView;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Service contract for querying dynamic product lead-time estimates.
 */
public interface InventoryLeadTimeService {

    /**
     * Returns lead-time data for a product at a location.
     *
     * @param productId         product identifier
     * @param locationId        location identifier
     * @param storageLocationId optional storage location identifier
     * @return lead-time view
     * @throws com.positivity.inventory.internal.exception.ResourceNotFoundException when no
     *     feed source carries lead-time data for the product
     */
    @NonNull
    LeadTimeView queryLeadTime(@NonNull UUID productId, @Nullable UUID locationId, @Nullable UUID storageLocationId);

    /**
     * Non-throwing variant of {@link #queryLeadTime} for internal callers that treat absent
     * feed data as a normal outcome (e.g. the replenishment lead-time resolution chain,
     * odoo-parity F2 issue #1040): absence must not raise an exception through a
     * transactional proxy, which would mark an enclosing transaction rollback-only.
     *
     * @return the lead-time view, or empty when no feed source carries data for the product
     */
    Optional<LeadTimeView> findLeadTime(
            @NonNull UUID productId, @Nullable UUID locationId, @Nullable UUID storageLocationId);
}
