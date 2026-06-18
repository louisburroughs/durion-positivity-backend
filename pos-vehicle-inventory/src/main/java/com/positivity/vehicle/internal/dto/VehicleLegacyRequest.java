package com.positivity.vehicle.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Legacy request payload for creating or updating a vehicle")
public class VehicleLegacyRequest {

    @Schema(
            description = "Identifier of the vehicle to update; omit to create a new vehicle",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID id;

    @Schema(description = "Manufacturer of the vehicle", example = "Honda", requiredMode = REQUIRED)
    @NotBlank
    private String make;

    @Schema(description = "Model of the vehicle", example = "Accord", requiredMode = REQUIRED)
    @NotBlank
    private String model;

    @Schema(description = "Model year of the vehicle", example = "2003", requiredMode = NOT_REQUIRED)
    private Integer year;

    @Schema(
            description = "17-character Vehicle Identification Number",
            example = "1HGCM82633A004352",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(min = 17, max = 17)
    private String vin;

    @Schema(
            description = "Vehicle type classification (CAR, VAN, COMMERCIAL_TRUCK, PASSENGER_TRUCK)",
            example = "CAR",
            requiredMode = NOT_REQUIRED)
    private String vehicleType;
}
