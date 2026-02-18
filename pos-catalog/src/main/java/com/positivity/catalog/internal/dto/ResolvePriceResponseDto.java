package com.positivity.catalog.internal.dto;

import java.util.UUID;
import lombok.Data;

@Data
public class ResolvePriceResponseDto {

    public enum ResolvePriceSource {
        PRICE_BOOK_RULE,
        MSRP,
        UNAVAILABLE
    }

    private String resolvedAmount;
    private String currency;
    private ResolvePriceSource source;
    private UUID sourceRuleId;
    private String fallbackReason;
}
