package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import org.jspecify.annotations.NonNull;

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
}
