package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Patch payload for bays.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Partial update payload for a service bay; null fields are left unchanged")
public class BayPatchRequest {

    @Schema(description = "Display name of the bay", example = "Bay A1", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Type classification of the bay; must be a BayType value",
            example = "GENERAL_SERVICE",
            requiredMode = NOT_REQUIRED)
    private String bayType;

    @Schema(description = "Operational status of the bay", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Number of vehicles the bay physically accommodates at once. A bay is a single "
                    + "bookable resource regardless of this value; register separate bays for independently "
                    + "bookable stalls.",
            example = "1",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer maxConcurrentVehicles;

    @Schema(description = "Capacity configuration for the bay", requiredMode = NOT_REQUIRED)
    @Valid
    private BayCapacityRequest capacity;

    @Schema(
            description = "Catalog operation codes this bay type is the only one able to perform "
                    + "(CAP-325 D14). Null leaves unchanged; an empty list clears to general. Each value must "
                    + "be an active catalog operationCode; unknown codes are rejected 422.",
            example = "[\"WHEEL-ALIGNMENT-4-WHEEL\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> serviceCapabilityCodes;

    @Schema(
            description = "Heaviest GVWR class (1–8) the bay accepts (CAP-325 D13). Null leaves unchanged.",
            example = "3",
            minimum = "1",
            maximum = "8",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    @Max(8)
    private Integer maxDutyClass;
}
