package com.positivity.price.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PricingAdjustment {

    private String type;
    private UUID sourceId;
    private String label;
    private BigDecimal amount;
    private Object metadata;
}
