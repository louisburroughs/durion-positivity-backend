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
 * Request DTO for correcting part quantity (administrative correction).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CorrectPartQuantityRequest {

    /**
     * ID of the workorder part.
     */
    @NotNull(message = "Workorder part ID is required")
    private UUID workorderPartId;

    /**
     * New authorized quantity (must be positive).
     */
    @NotNull(message = "New quantity is required")
    @Positive(message = "New quantity must be positive")
    private BigDecimal newQuantity;

    /**
     * Reason for correction (required for audit).
     */
    @NotNull(message = "Reason is required")
    private String reason;

    /**
     * Optional additional notes.
     */
    @Nullable
    private String notes;
}
