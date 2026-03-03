package com.positivity.workorder.internal.dto;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import jakarta.validation.constraints.DecimalMin;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request payload for partial update of an estimate line item")
public class UpdateEstimateItemRequest {

    @Nullable
    @Schema(description = "Updated description", example = "Front brake pad replacement")
    private String description;

    @Nullable
    @DecimalMin(value = "0.0001", message = "quantity must be greater than 0")
    @Schema(description = "Updated quantity", example = "2")
    private BigDecimal quantity;

    @Nullable
    @DecimalMin(value = "0.00", message = "unitPrice must be 0 or greater")
    @Schema(description = "Updated unit price", example = "59.99")
    private BigDecimal unitPrice;

    @Nullable
    @Schema(description = "Updated tax code", example = "TX-GENERAL")
    private String taxCode;
}
