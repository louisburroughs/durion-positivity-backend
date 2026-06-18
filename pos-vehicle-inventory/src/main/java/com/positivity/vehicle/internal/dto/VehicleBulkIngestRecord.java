package com.positivity.vehicle.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Single vehicle record within a bulk ingest request")
public class VehicleBulkIngestRecord {

    @Schema(
            description = "Identifier of the account that owns the vehicle",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID accountId;

    @Schema(
            description = "17-character Vehicle Identification Number",
            example = "1HGCM82633A004352",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(min = 17, max = 17)
    private String vin;

    @Schema(description = "Fleet unit number assigned to the vehicle", example = "UNIT-1042", requiredMode = REQUIRED)
    @NotBlank
    private String unitNumber;

    @Schema(
            description = "Human-readable description of the vehicle",
            example = "2003 Honda Accord EX Sedan",
            requiredMode = REQUIRED)
    @NotBlank
    private String description;

    @Schema(description = "License plate number", example = "ABC1234", requiredMode = NOT_REQUIRED)
    private String licensePlate;

    @Schema(
            description = "Jurisdiction (state/province) that issued the license plate",
            example = "CA",
            requiredMode = NOT_REQUIRED)
    private String licensePlateJurisdiction;

    @Schema(description = "Model year of the vehicle", example = "2003", requiredMode = NOT_REQUIRED)
    private Integer year;

    @Schema(description = "Manufacturer of the vehicle", example = "Honda", requiredMode = NOT_REQUIRED)
    private String make;

    @Schema(description = "Model of the vehicle", example = "Accord", requiredMode = NOT_REQUIRED)
    private String model;

    @Schema(description = "Trim level of the vehicle", example = "EX", requiredMode = NOT_REQUIRED)
    private String trim;
}
