package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a new cycle count adjustment.
 */
@Schema(description = "Request to create a new inventory adjustment from a cycle count variance")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAdjustmentRequest {

    @Schema(
            description = "Identifier of the stock item being adjusted",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull(message = "Stock item ID is required")
    private UUID stockItemId;

    @Schema(
            description = "Reason code explaining why the adjustment is being made",
            example = "CYCLE_COUNT_VARIANCE",
            requiredMode = REQUIRED)
    @NotBlank(message = "Reason code is required")
    private String reasonCode;

    @Schema(description = "Quantity physically counted by the auditor", example = "12", requiredMode = REQUIRED)
    @NotNull(message = "Counted quantity is required")
    @Min(value = 0, message = "Counted quantity cannot be negative")
    private Integer countedQuantity;

    @Schema(
            description = "Quantity on hand recorded before the count was applied",
            example = "15",
            requiredMode = REQUIRED)
    @NotNull(message = "Quantity on-hand before count is required")
    private Integer quantityOnHandBefore;

    @Schema(
            description = "Unit cost to capture at the time of the adjustment",
            example = "12.50",
            requiredMode = REQUIRED)
    @NotNull(message = "Cost at time of adjustment is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Cost must be non-negative")
    private BigDecimal costAtTimeOfAdjustment;

    @Schema(
            description = "Identifier of the user creating the adjustment",
            example = "user-10042",
            requiredMode = REQUIRED)
    @NotBlank(message = "Created by user ID is required")
    private String createdByUserId;
}
