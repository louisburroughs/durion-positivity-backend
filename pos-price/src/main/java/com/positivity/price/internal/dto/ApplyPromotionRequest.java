package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for applying a promotion code to an estimate context")
public class ApplyPromotionRequest {

    @Schema(description = "Promotion code to apply", requiredMode = Schema.RequiredMode.REQUIRED, example = "SUMMER20")
    @NotBlank(message = "promotionCode is required")
    @Size(max = 64, message = "promotionCode must not exceed 64 characters")
    private String promotionCode;

    @Schema(description = "Estimate context used for promotion evaluation", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "estimateContext is required")
    @Valid
    private EstimateContext estimateContext;
}
