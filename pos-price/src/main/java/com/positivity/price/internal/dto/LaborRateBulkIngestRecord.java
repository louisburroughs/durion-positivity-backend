package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** One hourly labor rate within a bulk ingest payload. */
@Data
@Schema(description = "Single labor-rate record within a bulk ingest payload")
public class LaborRateBulkIngestRecord {

    @Schema(
            description = "Location the rate belongs to; blank is the platform default that answers"
                    + " for every location which has authored none of its own. A file that names the"
                    + " site by its location code has the loader resolve it before the batch is posted",
            example = "0198f2a1-0000-7000-8000-00000000000a",
            requiredMode = NOT_REQUIRED)
    private String locationId;

    @Schema(
            description = "Category the rate applies to; blank is the scope's every-category rate",
            example = "TIRE_SERVICE",
            allowableValues = {"REPAIR", "DIAGNOSTIC", "MAINTENANCE", "TIRE_SERVICE"},
            requiredMode = NOT_REQUIRED)
    private String operationCategory;

    @Schema(description = "ISO-4217 currency code", example = "USD", requiredMode = REQUIRED)
    @NotBlank
    @Size(min = 3, max = 3, message = "currency must be an ISO-4217 3-character code")
    private String currency;

    @Schema(description = "Hourly rate charged for labor in this scope", example = "125.0000", requiredMode = REQUIRED)
    @NotBlank
    private String hourlyRate;

    @Schema(
            description = "Instant the rate takes effect, ISO-8601; part of the key the row is recognised by",
            example = "2026-01-01T00:00:00Z",
            requiredMode = REQUIRED)
    @NotBlank
    private String effectiveFrom;

    @Schema(
            description = "Instant the rate stops applying, ISO-8601; blank leaves the window open",
            example = "2027-01-01T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    private String effectiveTo;
}
