package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to return unused parts to inventory.
 * 
 * CAP:005 Story #158 - Parts Usage Tracking
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload to return unused issued parts to inventory")
public class ReturnPartRequest {

    @NotNull(message = "Workorder part ID is required")
    @Schema(description = "Workorder part identifier", example = "550e8400-e29b-41d4-a716-446655440500")
    private UUID workorderPartId;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.0001", inclusive = true, message = "Quantity must be positive")
    @Schema(description = "Quantity to return", example = "1")
    private BigDecimal quantity;

    @Nullable
    @Schema(description = "Optional return notes", example = "Returned to stock after over-issue")
    private String notes;
}
