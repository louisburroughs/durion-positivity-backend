package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for returning unused part quantity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnPartQuantityRequest {

    /**
     * ID of the workorder part.
     */
    @NotNull(message = "Workorder part ID is required")
    private UUID workorderPartId;

    /**
     * Quantity to return (must be positive).
     */
    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private BigDecimal quantity;

    /**
     * Reason for return (required for audit).
     */
    @NotNull(message = "Reason is required")
    private String reason;

    /**
     * Optional additional notes.
     */
    @Nullable
    private String notes;
}
