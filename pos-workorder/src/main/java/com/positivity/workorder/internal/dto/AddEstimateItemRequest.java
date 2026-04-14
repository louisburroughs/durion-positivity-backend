package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.EstimateItemType;
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
 * Request DTO for adding a line item to an estimate.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for adding a line item to an estimate")
public class AddEstimateItemRequest {

    @NotNull(message = "itemType is required")
    @Schema(description = "Type of estimate item", example = "PART")
    private EstimateItemType itemType;

    @Nullable
    @Schema(
            description = "Human-readable description when no catalog reference is provided",
            example = "Brake pad replacement")
    private String description;

    @jakarta.validation.constraints.AssertTrue(
            message = "description is required unless PART has productId or LABOR has serviceId")
    private boolean isDescriptionOrReferenceValid() {
        if (itemType == null) return true; // handled by @NotNull on itemType

        final boolean hasDescription = description != null && !description.isBlank();
        return switch (itemType) {
            case PART -> hasDescription || productId != null;
            case LABOR -> hasDescription || serviceId != null;
            default -> hasDescription;
        };
    }

    @NotNull(message = "quantity is required")
    @DecimalMin(value = "0.0001", message = "quantity must be greater than 0")
    @Schema(description = "Requested quantity", example = "2")
    private BigDecimal quantity;

    @NotNull(message = "unitPrice is required")
    @DecimalMin(value = "0.00", message = "unitPrice must be 0 or greater")
    @Schema(description = "Unit price for the line item", example = "49.99")
    private BigDecimal unitPrice;

    @Nullable
    @Schema(description = "Optional tax code", example = "TX-GENERAL")
    private String taxCode;

    @Nullable
    @Schema(
            description = "Referenced product identifier for PART items",
            example = "550e8400-e29b-41d4-a716-446655440020")
    private UUID productId; // For PART items

    @Nullable
    @Schema(
            description = "Referenced service identifier for LABOR items",
            example = "550e8400-e29b-41d4-a716-446655440021")
    private UUID serviceId; // For LABOR items
}
