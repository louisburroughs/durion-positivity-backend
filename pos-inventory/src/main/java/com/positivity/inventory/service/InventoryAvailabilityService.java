package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Service contract for reading inventory availability grouped by location.
 *
 * Issue: CAP-170 (#48)
 */
public interface InventoryAvailabilityService {

    /**
     * Returns availability for the requested product grouped by location.
     *
     * @param productId product identifier represented by stock item id in the
     *                  ledger
     * @return per-location availability list (empty when no ledger entries exist)
     */
    List<LocationAvailabilityDto> getAvailabilityByProduct(@NonNull UUID productId);

    /**
     * Returns availability for the requested product at a specific location.
     *
     * <p>
     * When {@code storageLocationId} is null, the result aggregates all
     * storage locations within the parent location.
     *
     * @param productSku        SKU identifier of the product
     * @param locationId        location identifier (required)
     * @param storageLocationId optional sub-location identifier
     * @return availability view with on-hand, allocated, and ATP quantities
     * @throws com.positivity.inventory.internal.exception.ProductNotFoundException  if
     *                                                                               productSku
     *                                                                               not
     *                                                                               found
     * @throws com.positivity.inventory.internal.exception.LocationNotFoundException if
     *                                                                               locationId
     *                                                                               not
     *                                                                               found
     *                                                                               Issue:
     *                                                                               CAP-215
     *                                                                               Story
     *                                                                               #36
     */
    @NonNull
    AvailabilityView queryAvailability(
            @NonNull String productSku,
            @NonNull String locationId,
            @Nullable String storageLocationId);
}
