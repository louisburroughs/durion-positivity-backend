package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request payload for returning unused authorized part quantity")
public class ReturnPartQuantityRequest {

    /**
     * ID of the workorder part.
     */
    @NotNull(message = "Workorder part ID is required")
    @Schema(description = "Workorder part identifier", example = "550e8400-e29b-41d4-a716-446655440500")
    private UUID workorderPartId;

    /**
     * Quantity to return (must be positive).
     */
    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    @Schema(description = "Quantity to return", example = "1")
    private BigDecimal quantity;

    /**
     * Reason for return (required for audit).
     */
    @NotNull(message = "Reason is required")
    @Schema(description = "Audit reason for return", example = "Part not needed after inspection")
    private String reason;

    /**
     * Optional additional notes.
     */
    @Nullable
    @Schema(description = "Optional return notes", example = "Returned unopened package")
    private String notes;
}
