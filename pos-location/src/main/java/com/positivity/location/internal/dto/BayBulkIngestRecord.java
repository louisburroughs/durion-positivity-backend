package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

/** A single service bay within a bulk ingest request. */
@Data
@Schema(description = "One service bay to create at a location")
public class BayBulkIngestRecord {

    @Schema(
            description = "Location the bay belongs to. Defaults to the request's locationId when omitted.",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "Name of the bay, unique within its location", example = "Bay 1", requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(description = "Bay type", example = "GENERAL_SERVICE", requiredMode = REQUIRED)
    @NotBlank
    private String bayType;

    @Schema(description = "How many vehicles the bay holds at once", example = "1", requiredMode = REQUIRED)
    @NotNull
    @Min(1)
    private Integer maxConcurrentVehicles;

    @Schema(description = "Bay status; defaults to ACTIVE", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;
}
