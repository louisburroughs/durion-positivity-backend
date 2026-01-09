package com.durion.inventory.api.dto;

import java.util.UUID;

public class DeactivateLocationRequest {
    private UUID destinationLocationId;

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
    }
}
