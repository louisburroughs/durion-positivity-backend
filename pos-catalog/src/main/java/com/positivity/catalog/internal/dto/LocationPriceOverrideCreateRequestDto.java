package com.positivity.catalog.internal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class LocationPriceOverrideCreateRequestDto {
    @NotNull
    private UUID locationId;

    @NotNull
    private UUID productId;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal basePrice;

    @DecimalMin(value = "0.0")
    private BigDecimal cost;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal overridePrice;

    @NotNull
    private UUID createdByUserId;
}
