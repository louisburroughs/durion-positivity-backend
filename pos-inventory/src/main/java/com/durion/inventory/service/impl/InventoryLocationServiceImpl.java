package com.durion.inventory.service.impl;

import com.durion.inventory.api.dto.DeactivateLocationResponse;
import com.durion.inventory.service.InventoryLocationService;
import org.springframework.stereotype.Service;

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
