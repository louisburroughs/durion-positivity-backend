package com.positivity.catalog.internal.dto;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class LocationPriceOverrideCreateRequestDto {
    private UUID locationId;
    private UUID productId;
    private BigDecimal basePrice;
    private BigDecimal cost;
    private BigDecimal overridePrice;
    private UUID createdByUserId;
}
