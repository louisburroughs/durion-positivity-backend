package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Request to deactivate a location, optionally moving its inventory to a destination location")
public class DeactivateLocationRequest {

    @Schema(
            description = "Optional destination location to which remaining inventory is transferred on deactivation",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID destinationLocationId;

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
    }
}
