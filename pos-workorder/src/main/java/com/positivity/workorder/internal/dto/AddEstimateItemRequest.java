package com.positivity.workorder.internal.dto;

import java.math.BigDecimal;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.positivity.workorder.internal.entity.EstimateItemType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Nullable
    private String description;

    @jakarta.validation.constraints.AssertTrue(message = "description is required unless PART has productId or LABOR has serviceId")
    private boolean isDescriptionOrReferenceValid() {
        if (itemType == null)
            return true; // handled by @NotNull on itemType

        final boolean hasDescription = description != null && !description.isBlank();
        return switch (itemType) {
            case PART -> hasDescription || productId != null;
            case LABOR -> hasDescription || serviceId != null;
            default -> hasDescription;
        };
    }

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
