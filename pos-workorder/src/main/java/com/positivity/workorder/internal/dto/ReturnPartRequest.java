package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
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
public class ReturnPartRequest {

    @NotNull(message = "Workorder part ID is required")
    private UUID workorderPartId;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.0001", inclusive = true, message = "Quantity must be positive")
    private BigDecimal quantity;

    @Nullable
    private String notes;
}
