package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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

    @Schema(description = "Type classification of the bay", example = "LIFT", requiredMode = REQUIRED)
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
            description = "Identifiers of service capabilities supported by the bay",
            example = "[\"01960003-0000-7000-8000-000000000010\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> serviceCapabilityIds;

    @Schema(
            description = "Identifiers of skills required to operate the bay",
            example = "[\"01960003-0000-7000-8000-000000000020\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> skillRequirementIds;

    @Schema(description = "Operational status of the bay", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;
}
