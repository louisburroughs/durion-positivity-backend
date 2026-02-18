package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.PriceOverrideStatus;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class EffectiveLocationPriceResponseDto {
    private UUID locationId;
    private UUID productId;
    private BigDecimal basePrice;
    private BigDecimal effectivePrice;
    private PriceOverrideStatus overrideStatus;
}
