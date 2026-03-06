package com.positivity.vehiclefitment.internal.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle type response for a make")
public class VehicleTypeResponse {
    @Schema(description = "Vehicle type identifier", requiredMode = Schema.RequiredMode.REQUIRED, example = "550e8400-e29b-41d4-a716-446655440030")
    UUID id;
    @Schema(description = "Make identifier for this vehicle type", example = "550e8400-e29b-41d4-a716-446655440010")
    UUID makeId;
    @Schema(description = "External vehicle type code", requiredMode = Schema.RequiredMode.REQUIRED, example = "CAR")
    String vehicleTypeId;
    @Schema(description = "Human-readable vehicle type name", requiredMode = Schema.RequiredMode.REQUIRED, example = "Passenger Car")
    String vehicleTypeName;
}
