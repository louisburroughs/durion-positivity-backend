package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** One labor-matrix adjustment step within a bulk ingest payload. */
@Data
@Schema(description = "Single labor-matrix adjustment record within a bulk ingest payload")
public class LaborRateAdjustmentBulkIngestRecord {

    @Schema(
            description = "Location the step belongs to; blank is the platform default. A file that"
                    + " names the site by its location code has the loader resolve it before posting",
            example = "0198f2a1-0000-7000-8000-00000000000a",
            requiredMode = NOT_REQUIRED)
    private String locationId;

    @Schema(
            description = "Category the step applies to; blank applies it to every category",
            example = "TIRE_SERVICE",
            allowableValues = {"REPAIR", "DIAGNOSTIC", "MAINTENANCE", "TIRE_SERVICE"},
            requiredMode = NOT_REQUIRED)
    private String operationCategory;

    @Schema(description = "Code a caller opts into to apply the step", example = "CORROSION", requiredMode = REQUIRED)
    @NotBlank
    private String adjustmentCode;

    @Schema(
            description = "What condition the step prices",
            example = "Seized or corroded fasteners requiring heat, penetrant or extraction",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "Whether the value is a percentage of the running rate or a flat amount",
            example = "PERCENT",
            allowableValues = {"PERCENT", "FIXED"},
            requiredMode = REQUIRED)
    @NotBlank
    private String adjustmentType;

    @Schema(
            description = "The percentage or amount; negative for a discount",
            example = "15.0000",
            requiredMode = REQUIRED)
    @NotBlank
    private String adjustmentValue;

    @Schema(
            description = "Order the step compounds in; percentage steps do not commute, so this is"
                    + " part of what the matrix means rather than a display preference",
            example = "10",
            requiredMode = REQUIRED)
    @NotBlank
    private String sequence;

    @Schema(
            description = "Instant the step takes effect, ISO-8601; part of the key the row is recognised by",
            example = "2026-01-01T00:00:00Z",
            requiredMode = REQUIRED)
    @NotBlank
    private String effectiveFrom;

    @Schema(
            description = "Instant the step stops applying, ISO-8601; blank leaves the window open",
            example = "2027-01-01T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    private String effectiveTo;
}
