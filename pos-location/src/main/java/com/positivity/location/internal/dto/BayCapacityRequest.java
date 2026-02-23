package com.positivity.location.internal.dto;

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
public class BayCapacityRequest {

    @NotNull
    @Min(1)
    private Integer maxConcurrentVehicles;
}
