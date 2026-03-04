package com.positivity.inventory.internal.dto.cyclecount;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for creating a new cycle count adjustment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAdjustmentRequest {
    
    @NotNull(message = "Stock item ID is required")
    private UUID stockItemId;
    
    @NotBlank(message = "Reason code is required")
    private String reasonCode;
    
    @NotNull(message = "Counted quantity is required")
    @Min(value = 0, message = "Counted quantity cannot be negative")
    private Integer countedQuantity;
    
    @NotNull(message = "Quantity on-hand before count is required")
    private Integer quantityOnHandBefore;
    
    @NotNull(message = "Cost at time of adjustment is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Cost must be non-negative")
    private BigDecimal costAtTimeOfAdjustment;
    
    @NotBlank(message = "Created by user ID is required")
    private String createdByUserId;
}
