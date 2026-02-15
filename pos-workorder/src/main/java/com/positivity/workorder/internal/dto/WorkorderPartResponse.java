package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.workorder.internal.entity.WorkorderItemStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response DTO for workorder part line items with usage totals.
 * 
 * <p>
 * Implements CAP-005 Story #155 - Role-Based Visibility
 * <p>
 * Includes fields from CAP-005 Story #158 - Parts Usage Tracking
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Workorder part line item with usage totals")
public class WorkorderPartResponse {

    @Schema(description = "Part line item ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Product entity ID from catalog", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID productEntityId;

    @Schema(description = "Part description", example = "Brake Rotor - Front")
    private String description;

    @Schema(description = "Part line status", example = "OPEN")
    private WorkorderItemStatus status;

    @Schema(description = "Authorized quantity from estimate", example = "2.00")
    private BigDecimal quantity;

    @Schema(description = "Unit price per part", example = "65.00")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal unitPrice;

    @Schema(description = "Quantity issued from inventory", example = "2.00")
    private BigDecimal quantityIssued;

    @Schema(description = "Quantity consumed (cannot be returned)", example = "1.50")
    private BigDecimal quantityConsumed;

    @Schema(description = "Quantity returned to inventory", example = "0.50")
    private BigDecimal quantityReturned;

    @Schema(description = "Total part cost (authorized quantity * unit price)", example = "130.00")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal partCost;

    @Schema(description = "Line total from estimate", example = "130.00")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal lineTotal;
}
