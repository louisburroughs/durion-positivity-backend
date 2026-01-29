package com.positivity.order.internal.dto;

import lombok.Data;

/**
 * Request DTO for approving a price override.
 */
@Data
public class ApprovePriceOverrideRequest {
    private String comments;
}
