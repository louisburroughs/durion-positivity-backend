package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating bays.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for creating a service bay")
public class BayRequest {

    @Schema(description = "Display name of the bay", example = "Bay A1", requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(
            description = "Type classification of the bay; must be a BayType value",
            example = "GENERAL_SERVICE",
            requiredMode = REQUIRED)
    @NotBlank
    private String bayType;

    @Schema(description = "Capacity configuration for the bay", requiredMode = REQUIRED)
    @NotNull
    @Valid
    private BayCapacityRequest capacity;

    @Schema(
            description = "Maximum number of vehicles that can be serviced concurrently in the bay",
            example = "2",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer maxConcurrentVehicles;

    @Schema(
            description = "Catalog operation codes this bay type is the only one able to perform "
                    + "(CAP-325 D14). Omit or send empty for a general bay. Each value must be an active "
                    + "catalog operationCode (UPPER-DASH, ADR-0059 §3); unknown codes are rejected 422.",
            example = "[\"WHEEL-ALIGNMENT-4-WHEEL\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> serviceCapabilityCodes;

    @Schema(
            description = "Heaviest GVWR class (1–8) the bay accepts; omit for unconstrained (CAP-325 D13).",
            example = "3",
            minimum = "1",
            maximum = "8",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    @Max(8)
    private Integer maxDutyClass;

    @Schema(description = "Operational status of the bay", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;
}
