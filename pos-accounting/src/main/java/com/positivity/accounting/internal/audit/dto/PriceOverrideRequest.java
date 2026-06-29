package com.positivity.accounting.internal.audit.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request to record a price override audit event")
public class PriceOverrideRequest {

    @Schema(
            description = "Identifier of the order containing the line item",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull(message = "Order ID is required")
    private UUID orderId;

    @Schema(
            description = "Identifier of the line item being overridden",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull(message = "Line item ID is required")
    private UUID lineItemId;

    @Schema(description = "Original price before the override", example = "1250.00", requiredMode = REQUIRED)
    @NotNull(message = "Original price is required")
    private BigDecimal originalPrice;

    @Schema(description = "Adjusted price after the override", example = "1100.00", requiredMode = REQUIRED)
    @NotNull(message = "Adjusted price is required")
    private BigDecimal adjustedPrice;

    @Schema(
            description = "Role of the actor performing the override",
            example = "STORE_MANAGER",
            requiredMode = REQUIRED)
    @NotNull(message = "Actor role is required")
    private String actorRole;

    @Schema(
            description = "Reason for the price override",
            example = "Price matched competitor quote",
            requiredMode = REQUIRED)
    @NotNull(message = "Reason is required")
    private String reason;

    /**
     * Optional forbidden category code (e.g., BELOW_COST, STACKING_VIOLATION).
     */
    @Schema(
            description = "Optional forbidden category code (e.g., BELOW_COST, STACKING_VIOLATION)",
            example = "BELOW_COST",
            requiredMode = NOT_REQUIRED)
    private String categoryCode;
}
