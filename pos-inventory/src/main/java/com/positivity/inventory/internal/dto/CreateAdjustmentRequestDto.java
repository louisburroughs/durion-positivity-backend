package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.validation.NonZero;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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

    /**
     * Longest reason the adjustment accepts (#2628): the width of {@code reason_code} on the request
     * and on the ledger row it is copied to at approval, so it is the end-to-end limit for the reason
     * carried on {@code InventoryAdjustedV1} and into accounting's journal-entry description.
     */
    public static final int REASON_CODE_MAX_LENGTH = 100;

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
            description = "Adjustment quantity, non-zero (positive to add stock, negative to remove); zero is rejected",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    @NonZero
    BigDecimal quantity;

    /** Mandatory reason code explaining the adjustment, at most {@value #REASON_CODE_MAX_LENGTH} characters. */
    @Schema(
            description = "Mandatory reason code explaining the adjustment, at most 100 characters; a longer"
                    + " reason is refused with 400 VALIDATION_ERROR and a field error on reasonCode",
            example = "CYCLE_COUNT",
            maxLength = REASON_CODE_MAX_LENGTH,
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = REASON_CODE_MAX_LENGTH)
    String reasonCode;

    @Schema(
            description = "Optional unit of measure code for the quantity (e.g. EACH, KG)",
            example = "EACH",
            requiredMode = NOT_REQUIRED)
    String unitOfMeasure;
}
