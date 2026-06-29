package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "Response payload after applying a promotion to an estimate")
public class ApplyPromotionResponse {

    @Schema(description = "Original estimate subtotal before promotions", example = "500.00", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal subtotal;

    @Schema(
            description = "Final estimate total after applying adjustments",
            example = "400.00",
            requiredMode = REQUIRED)
    @NotNull
    private BigDecimal total;

    @Schema(description = "Adjustments applied during promotion evaluation", example = "[]", requiredMode = REQUIRED)
    @NotNull
    private List<PricingAdjustment> appliedAdjustments;
}
