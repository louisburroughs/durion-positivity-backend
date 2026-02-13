package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.EstimateItemType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for adding a line item to an estimate.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddEstimateItemRequest {

    @NotNull(message = "itemType is required")
    private EstimateItemType itemType;

    @NotBlank(message = "description is required")
    private String description;

    @NotNull(message = "quantity is required")
    @DecimalMin(value = "0.0001", message = "quantity must be greater than 0")
    private BigDecimal quantity;

    @NotNull(message = "unitPrice is required")
    @DecimalMin(value = "0.00", message = "unitPrice must be 0 or greater")
    private BigDecimal unitPrice;

    @Nullable
    private String taxCode;

    @Nullable
    private UUID productId; // For PART items

    @Nullable
    private UUID serviceId; // For LABOR items
}
