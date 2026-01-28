package com.positivity.inventory.service;

import java.util.UUID;

import com.positivity.inventory.dto.DeactivateLocationResponse;

public interface InventoryLocationService {
    /**
     * Deactivate a location, performing an atomic stock transfer to destination
     * when required.
     * Business policy as per story #39 and clarification #236 (Option B).
     */
    DeactivateLocationResponse deactivateLocation(UUID locationId, UUID destinationLocationId);
}
