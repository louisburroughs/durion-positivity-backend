package com.durion.inventory.service;

import com.durion.inventory.api.dto.DeactivateLocationResponse;

import java.util.UUID;

public interface InventoryLocationService {
    /**
     * Deactivate a location, performing an atomic stock transfer to destination when required.
     * Business policy as per story #39 and clarification #236 (Option B).
     */
    DeactivateLocationResponse deactivateLocation(UUID locationId, UUID destinationLocationId);
}
