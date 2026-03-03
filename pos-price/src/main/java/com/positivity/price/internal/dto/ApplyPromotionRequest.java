package com.positivity.price.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ApplyPromotionRequest {

    @NotBlank
    private String promotionCode;

    @NotNull
    @Valid
    private EstimateContext estimateContext;
}
