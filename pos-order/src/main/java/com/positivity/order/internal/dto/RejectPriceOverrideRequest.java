package com.positivity.order.internal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for rejecting a price override.
 */
@Data
public class RejectPriceOverrideRequest {
    
    @NotBlank(message = "Rejection reason is required")
    private String reason;
    
    private String comments;
}
