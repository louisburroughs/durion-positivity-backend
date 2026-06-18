package com.positivity.vehiclefitment.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Single fitment record consumed during bulk part fitment ingest")
public class FitmentBulkIngestRecord {

    @Schema(
            description = "Part number identifier the fitment record applies to",
            example = "100245",
            requiredMode = REQUIRED)
    @NotNull
    private Long partNumberId;

    @Schema(description = "Manufacturer name for the vehicle", example = "Toyota", requiredMode = NOT_REQUIRED)
    private String manufacturerName;

    @Schema(description = "Make name for the vehicle", example = "Camry", requiredMode = NOT_REQUIRED)
    private String makeName;

    @Schema(description = "Model name for the vehicle", example = "Camry SE", requiredMode = NOT_REQUIRED)
    private String modelName;

    @Schema(description = "Vehicle type name", example = "Passenger Car", requiredMode = NOT_REQUIRED)
    private String vehicleTypeName;

    @Schema(description = "Vehicle year or year range", example = "2018-2022", requiredMode = NOT_REQUIRED)
    private String vehicleYear;

    @Schema(description = "Engine type for the vehicle", example = "2.5L I4", requiredMode = NOT_REQUIRED)
    private String engineType;

    @Schema(description = "Vehicle submodel or trim", example = "SE", requiredMode = NOT_REQUIRED)
    private String submodel;

    @Schema(
            description = "Free-form notes about the fitment",
            example = "Verified against OEM catalog",
            requiredMode = NOT_REQUIRED)
    private String notes;
}
