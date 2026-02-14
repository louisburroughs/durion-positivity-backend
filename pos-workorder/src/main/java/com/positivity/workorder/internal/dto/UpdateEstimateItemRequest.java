package com.positivity.workorder.internal.dto;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating an existing line item on an estimate.
 * All fields are optional - only provided fields will be updated.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateEstimateItemRequest {

    @Nullable
    private String description;

    @Nullable
    @DecimalMin(value = "0.0001", message = "quantity must be greater than 0")
    private BigDecimal quantity;

    @Nullable
    @DecimalMin(value = "0.00", message = "unitPrice must be 0 or greater")
    private BigDecimal unitPrice;

    @Nullable
    private String taxCode;
}
