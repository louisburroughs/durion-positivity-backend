package com.positivity.nhtsa.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle type reference sourced from the NHTSA dataset")
public class VehicleTypeResponse {

    @Schema(
            description = "Internal record identifier for the vehicle type reference entry",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    @NotNull
    UUID id;

    @Schema(
            description = "Identifier of the make this vehicle type is associated with",
            example = "550e8400-e29b-41d4-a716-446655440001",
            requiredMode = REQUIRED)
    @NotNull
    UUID makeId;

    @Schema(description = "Provider vehicle type identifier from NHTSA", example = "2", requiredMode = REQUIRED)
    @NotNull
    String vehicleTypeId;

    @Schema(description = "Human-readable vehicle type name", example = "Truck", requiredMode = REQUIRED)
    @NotNull
    String vehicleTypeName;
}
