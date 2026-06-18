package com.positivity.vehicle.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Vehicle summary for search results (CAP:091 Story #103).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Condensed vehicle representation returned in search results")
public class VehicleSummary {

    @Schema(
            description = "Unique identifier of the vehicle",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID vehicleId;

    @Schema(
            description = "17-character Vehicle Identification Number",
            example = "1HGCM82633A004352",
            requiredMode = REQUIRED)
    private String vin;

    @Schema(description = "Fleet unit number assigned to the vehicle", example = "UNIT-1042", requiredMode = NOT_REQUIRED)
    private String unitNumber;

    @Schema(description = "License plate number", example = "ABC1234", requiredMode = NOT_REQUIRED)
    private String licensePlate;

    @Schema(
            description = "Human-readable description of the vehicle",
            example = "2003 Honda Accord EX Sedan",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Model year of the vehicle", example = "2003", requiredMode = NOT_REQUIRED)
    private Integer year;

    @Schema(description = "Manufacturer of the vehicle", example = "Honda", requiredMode = NOT_REQUIRED)
    private String make;

    @Schema(description = "Model of the vehicle", example = "Accord", requiredMode = NOT_REQUIRED)
    private String model;
}
