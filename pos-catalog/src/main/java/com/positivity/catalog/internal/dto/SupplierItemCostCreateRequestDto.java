package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for creating supplier-item cost tiers")
public class SupplierItemCostCreateRequestDto {

    @Schema(description = "Supplier identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID supplierId;

    @Schema(description = "Inventory item identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID itemId;

    @Schema(description = "ISO 4217 currency code", example = "USD", requiredMode = Schema.RequiredMode.REQUIRED)
    private String currencyCode;

    @Schema(description = "Optional fallback base cost when no tiers apply", example = "6.25")
    private BigDecimal baseCost;

    @Schema(description = "Ordered quantity-based cost tiers")
    private List<CostTierDto> tiers;
}
