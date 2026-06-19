package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Capacity payload for bay requests.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Capacity configuration for a service bay")
public class BayCapacityRequest {

    @Schema(
            description = "Maximum number of vehicles that can be serviced concurrently in the bay",
            example = "2",
            requiredMode = REQUIRED)
    @NotNull
    @Min(1)
    private Integer maxConcurrentVehicles;
}
