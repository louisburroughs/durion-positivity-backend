package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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

    @Schema(description = "Type classification of the bay", example = "LIFT", requiredMode = NOT_REQUIRED)
    private String bayType;

    @Schema(description = "Operational status of the bay", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Maximum number of vehicles that can be serviced concurrently in the bay",
            example = "2",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer maxConcurrentVehicles;

    @Schema(description = "Capacity configuration for the bay", requiredMode = NOT_REQUIRED)
    @Valid
    private BayCapacityRequest capacity;

    @Schema(
            description = "Identifiers of service capabilities supported by the bay",
            example = "[\"01960003-0000-7000-8000-000000000010\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> serviceCapabilityIds;

    @Schema(
            description = "Identifiers of skills required to operate the bay",
            example = "[\"01960003-0000-7000-8000-000000000020\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> skillRequirementIds;
}
