package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.LeadTimeView;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service contract for querying dynamic product lead-time estimates.
 */
public interface InventoryLeadTimeService {

    /**
     * Returns lead-time data for a product at a location.
     *
     * @param productId  product identifier
     * @param locationId location identifier
     * @return lead-time view
     */
    @NonNull
    LeadTimeView queryLeadTime(@NonNull UUID productId, @NonNull UUID locationId);
}
