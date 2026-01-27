package com.positivity.workorder.dto;

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
    private Long customerId;
    
    @NotNull(message = "vehicleId is required")
    private Long vehicleId;
    
    private Long locationId; // Optional - will use default from session if not provided
    private String currencyUomId; // Optional - will use default if not provided
    private Long taxRegionId; // Optional - will use default if not provided
}
