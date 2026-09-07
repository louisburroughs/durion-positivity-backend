package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * One service operation within a bulk-ingest payload.
 *
 * <p>Every field is text, as on the other bulk-ingest records: the loader reads its files as
 * strings and posts them through unchanged, so a value that will not parse is reported as that
 * row's failure rather than rejecting the whole batch at deserialisation.
 */
@Data
@Schema(description = "Single service-operation record within a bulk ingest payload")
public class CatalogServiceBulkIngestRecord {

    @Schema(
            description = "Durion operation code; the natural key the row is upserted on",
            example = "TPMS-SENSOR-SERVICE",
            requiredMode = REQUIRED)
    @NotBlank
    private String operationCode;

    @Schema(
            description = "Operation name shown to a writer",
            example = "TPMS Service Kit - Set of 4",
            requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(
            description = "One-line summary of the operation",
            example = "Rebuild and reset four TPMS sensors",
            requiredMode = NOT_REQUIRED)
    private String shortDescription;

    @Schema(
            description = "Full description of what the operation covers",
            example = "Replace TPMS service kits on all four sensors, test transmission, relearn positions.",
            requiredMode = NOT_REQUIRED)
    private String longDescription;

    @Schema(
            description = "Operation category",
            example = "TIRE_SERVICE",
            allowableValues = {"REPAIR", "DIAGNOSTIC", "MAINTENANCE", "TIRE_SERVICE"},
            requiredMode = NOT_REQUIRED)
    private String operationCategory;

    @Schema(
            description = "Vehicle-agnostic fallback labor hours, decimal hours in tenths",
            example = "0.6",
            requiredMode = NOT_REQUIRED)
    private String defaultLaborHours;
}
