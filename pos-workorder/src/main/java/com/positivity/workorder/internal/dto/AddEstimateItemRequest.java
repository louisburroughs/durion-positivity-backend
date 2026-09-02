package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
    @Schema(description = "Type of estimate item", example = "PART", requiredMode = REQUIRED)
    private EstimateItemType itemType;

    @Nullable
    @Schema(
            description = "Human-readable description when no catalog reference is provided",
            example = "Brake pad replacement",
            requiredMode = NOT_REQUIRED)
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

    @Nullable
    @DecimalMin(value = "0.0001", message = "quantity must be greater than 0")
    @Schema(
            description = "Requested quantity (hours for LABOR). Required, EXCEPT on a LABOR item that names a"
                    + " serviceId: omitting it there asks the labor guide to prefill the book time (#1569);"
                    + " when no guide time is available the request is rejected and an explicit quantity must"
                    + " be sent. A supplied quantity always wins over the guide.",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private BigDecimal quantity;

    @jakarta.validation.constraints.AssertTrue(
            message = "quantity is required unless a LABOR item names a serviceId for guide defaulting")
    private boolean isQuantityPresentWhenRequired() {
        if (quantity != null) {
            return true;
        }
        return itemType == EstimateItemType.LABOR && serviceId != null;
    }

    @NotNull(message = "unitPrice is required")
    @DecimalMin(value = "0.00", message = "unitPrice must be 0 or greater")
    @Schema(description = "Unit price for the line item", example = "49.99", requiredMode = REQUIRED)
    private BigDecimal unitPrice;

    @Nullable
    @Schema(description = "Optional tax code", example = "TX-GENERAL", requiredMode = NOT_REQUIRED)
    private String taxCode;

    @Nullable
    @Schema(
            description = "Referenced product identifier for PART items",
            example = "550e8400-e29b-41d4-a716-446655440020",
            requiredMode = NOT_REQUIRED)
    private UUID productId; // For PART items

    @Nullable
    @Schema(
            description = "Referenced service identifier for LABOR items",
            example = "550e8400-e29b-41d4-a716-446655440021",
            requiredMode = NOT_REQUIRED)
    private UUID serviceId; // For LABOR items

    @Nullable
    @Schema(
            description = "Unit quantity is expressed in, for PART items only (e.g. \"QT\", \"CASE\"). Omit for the "
                    + "product's base unit -- today's implicit behavior. LABOR items must omit this field; hours "
                    + "carry no catalog unit-of-measure conversion.",
            example = "QT",
            requiredMode = NOT_REQUIRED)
    private String uomCode;

    @jakarta.validation.constraints.AssertTrue(message = "uomCode is not valid for LABOR items")
    private boolean isUomCodeValidForItemType() {
        return itemType != EstimateItemType.LABOR || uomCode == null;
    }
}
