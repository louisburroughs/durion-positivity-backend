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
 * Request DTO for correcting part quantity (administrative correction).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for administratively correcting authorized part quantity")
public class CorrectPartQuantityRequest {

    /**
     * ID of the workorder part.
     */
    @NotNull(message = "Workorder part ID is required")
    @Schema(description = "Workorder part identifier", example = "550e8400-e29b-41d4-a716-446655440500")
    private UUID workorderPartId;

    /**
     * New authorized quantity (must be positive).
     */
    @NotNull(message = "New quantity is required")
    @Positive(message = "New quantity must be positive")
    @Schema(description = "Corrected authorized quantity", example = "3")
    private BigDecimal newQuantity;

    /**
     * Reason for correction (required for audit).
     */
    @NotNull(message = "Reason is required")
    @Schema(description = "Audit reason for correction", example = "Inventory recount adjusted authorized quantity")
    private String reason;

    /**
     * Optional additional notes.
     */
    @Nullable
    @Schema(description = "Optional correction notes", example = "Adjusted after parts counter verification")
    private String notes;
}
