package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for creating a draft inventory adjustment request.
 *
 * Issue: CAP-215 Story #37
 */
@Schema(description = "Request body for creating a draft inventory adjustment request")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAdjustmentRequestDto {

    @Schema(description = "Stock-keeping unit of the product to adjust", example = "SKU-10042", requiredMode = REQUIRED)
    @NotBlank
    String productSku;

    @Schema(
            description = "Identifier of the location where the adjustment applies",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    UUID locationId;

    @Schema(
            description = "Adjustment quantity (positive to add stock, negative to remove)",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    Integer quantity;

    /** Mandatory reason code explaining the adjustment. */
    @Schema(
            description = "Mandatory reason code explaining the adjustment",
            example = "CYCLE_COUNT",
            requiredMode = REQUIRED)
    @NotBlank
    String reasonCode;

    @Schema(
            description = "Optional unit of measure code for the quantity (e.g. EACH, KG)",
            example = "EACH",
            requiredMode = NOT_REQUIRED)
    String unitOfMeasure;
}
