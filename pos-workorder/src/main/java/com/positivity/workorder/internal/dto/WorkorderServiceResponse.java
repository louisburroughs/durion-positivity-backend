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
 * Response DTO for workorder service line items with labor totals.
 * 
 * <p>
 * Implements CAP-005 Story #155 - Role-Based Visibility
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Workorder service line item with labor totals")
public class WorkorderServiceResponse {

    @Schema(description = "Service line item ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Service entity ID from catalog", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID serviceEntityId;

    @Schema(description = "Service description", example = "Brake Pad Replacement")
    private String description;

    @Schema(description = "Service line status", example = "OPEN")
    private WorkorderItemStatus status;

    @Schema(description = "Authorized quantity (hours for labor)", example = "2.5")
    private BigDecimal quantity;

    @Schema(description = "Unit price (hourly rate)", example = "85.00")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal unitPrice;

    @Schema(description = "Total labor hours recorded", example = "1.5")
    private BigDecimal totalLaborHours;

    @Schema(description = "Total labor cost", example = "127.50")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal laborCost;

    @Schema(description = "Line total from estimate", example = "212.50")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal lineTotal;
}
