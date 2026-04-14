package com.positivity.accounting.internal.audit.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to record a price override.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceOverrideRequest {

    @NotNull(message = "Order ID is required")
    private UUID orderId;

    @NotNull(message = "Line item ID is required")
    private UUID lineItemId;

    @NotNull(message = "Original price is required")
    private BigDecimal originalPrice;

    @NotNull(message = "Adjusted price is required")
    private BigDecimal adjustedPrice;

    @NotNull(message = "Actor role is required")
    private String actorRole;

    @NotNull(message = "Reason is required")
    private String reason;

    /**
     * Optional forbidden category code (e.g., BELOW_COST, STACKING_VIOLATION).
     */
    private String categoryCode;
}
