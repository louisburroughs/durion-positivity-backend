package com.positivity.vehicle.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Legacy vehicle representation returned by vehicle endpoints")
public class VehicleLegacyResponse {

    @Schema(
            description = "Unique identifier of the vehicle",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID id;

    @Schema(description = "Manufacturer of the vehicle", example = "Honda", requiredMode = REQUIRED)
    String make;

    @Schema(description = "Model of the vehicle", example = "Accord", requiredMode = REQUIRED)
    String model;

    @Schema(description = "Model year of the vehicle", example = "2003", requiredMode = REQUIRED)
    int year;

    @Schema(
            description = "17-character Vehicle Identification Number",
            example = "1HGCM82633A004352",
            requiredMode = REQUIRED)
    String vin;

    @Schema(
            description = "Vehicle type classification (CAR, VAN, COMMERCIAL_TRUCK, PASSENGER_TRUCK)",
            example = "CAR",
            requiredMode = REQUIRED)
    String vehicleType;

    @Schema(
            description = "Timestamp when the vehicle record was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    Instant createdAt;

    @Schema(
            description = "Timestamp when the vehicle record was last updated",
            example = "2026-01-16T14:45:00Z",
            requiredMode = NOT_REQUIRED)
    Instant updatedAt;
}
