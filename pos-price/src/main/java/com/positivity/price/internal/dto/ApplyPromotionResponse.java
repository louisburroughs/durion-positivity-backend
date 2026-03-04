package com.positivity.price.internal.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApplyPromotionResponse {

    private BigDecimal subtotal;
    private BigDecimal total;
    private List<PricingAdjustment> appliedAdjustments;
}
