package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.Data;

/** A single mobile unit within a bulk ingest request. */
@Data
@Schema(description = "One mobile unit to create")
public class MobileUnitBulkIngestRecord {

    @Schema(description = "Name of the unit, unique for its base location", example = "Van 01", requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(
            description = "Location the unit is based at. Defaults to the request's locationId when omitted.",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
            requiredMode = NOT_REQUIRED)
    private UUID baseLocationId;

    @Schema(
            description =
                    "Unit status; defaults to OUT_OF_SERVICE (reason OTHER). A unit created ACTIVE must also carry a travel"
                            + " buffer policy, capabilities and coverage rules, none of which this record expresses —"
                            + " so an ACTIVE row is rejected by the service rather than created half-configured.",
            example = "OUT_OF_SERVICE",
            requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(description = "Free-text notes", example = "Mobile tyre fitting", requiredMode = NOT_REQUIRED)
    private String notes;

    @Schema(
            description = "Heaviest GVWR class (1-8) the unit accepts; omit for unconstrained "
                    + "(DECISION-LOCATION-029, the bay's axis per CAP-325 D13).",
            example = "3",
            minimum = "1",
            maximum = "8",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    @Max(8)
    private Integer maxDutyClass;
}
