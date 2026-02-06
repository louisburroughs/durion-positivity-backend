package com.positivity.workorder.internal.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateEstimateRequest {
    @NotNull(message = "customerId is required")
    private UUID customerId;

    @NotNull(message = "vehicleId is required")
    private UUID vehicleId;

    private UUID locationId; // Optional - will use default from session if not provided
    private String currencyUomId; // Optional - will use default if not provided
    private UUID taxRegionId; // Optional - will use default if not provided
}
