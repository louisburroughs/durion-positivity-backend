package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request to issue parts to a workorder.
 *
 * CAP:005 Story #158 - Parts Usage Tracking
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload to issue authorized part quantity to a workorder")
public class IssuePartRequest {

    @NotNull(message = "Workorder part ID is required")
    @Schema(description = "Workorder part identifier", example = "550e8400-e29b-41d4-a716-446655440500")
    private UUID workorderPartId;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.0001", inclusive = true, message = "Quantity must be positive")
    @Schema(description = "Quantity to issue", example = "1")
    private BigDecimal quantity;

    @Nullable
    @Schema(description = "Optional issue notes", example = "Issued from bin A-14")
    private String notes;
}
