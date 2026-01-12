package com.positivity.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create a new draft estimate")
public class CreateEstimateRequest {
    
    @NotNull(message = "Customer ID is required")
    @Schema(description = "ID of the customer for whom the estimate is being created", example = "123", required = true)
    private Long customerId;
    
    @NotNull(message = "Vehicle ID is required")
    @Schema(description = "ID of the vehicle to be serviced", example = "456", required = true)
    private Long vehicleId;
    
    @Schema(description = "ID of the shop where the service will be performed", example = "1")
    private Long shopId;
}
