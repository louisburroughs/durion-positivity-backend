package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

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
    @Schema(
            description = "Workorder part identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID workorderPartId;

    /**
     * New authorized quantity (must be positive).
     */
    @NotNull(message = "New quantity is required")
    @Positive(message = "New quantity must be positive")
    @Schema(description = "Corrected authorized quantity", example = "2", requiredMode = REQUIRED)
    private BigDecimal newQuantity;

    /**
     * Reason for correction (required for audit).
     */
    @NotBlank(message = "Reason is required")
    @Schema(
            description = "Audit reason for correction",
            example = "Inventory recount adjusted authorized quantity",
            requiredMode = REQUIRED)
    private String reason;

    /**
     * Optional additional notes.
     */
    @Nullable
    @Schema(
            description = "Optional correction notes",
            example = "Adjusted after parts counter verification",
            requiredMode = NOT_REQUIRED)
    private String notes;
}
