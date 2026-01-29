package com.positivity.inventory.service.impl;

import org.springframework.stereotype.Service;

import com.positivity.inventory.internal.dto.DeactivateLocationResponse;
import com.positivity.inventory.service.InventoryLocationService;

import java.util.UUID;

@Service
public class InventoryLocationServiceImpl implements InventoryLocationService {
    @Override
    public DeactivateLocationResponse deactivateLocation(UUID locationId, UUID destinationLocationId) {
        // TODO: Implement policy Option B
        // - Validate source exists and is Active
        // - If source has stock, require valid destination (same Site, Active)
        // - Perform transactional transfer of all stock to destination
        // - Mark source as Inactive
        // - Publish audit event and metrics
        DeactivateLocationResponse resp = new DeactivateLocationResponse();
        resp.setSourceLocationId(locationId);
        resp.setDestinationLocationId(destinationLocationId);
        resp.setStatus("Inactive");
        return resp;
    }
}
