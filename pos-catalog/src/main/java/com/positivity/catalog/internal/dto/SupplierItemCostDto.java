package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Supplier-item cost structure with optional quantity-based tiers")
public class SupplierItemCostDto {

    @Schema(description = "Supplier-item cost identifier")
    private UUID id;

    @Schema(description = "Supplier identifier")
    private UUID supplierId;

    @Schema(description = "Inventory item identifier")
    private UUID itemId;

    @Schema(description = "ISO 4217 currency code", example = "USD")
    private String currencyCode;

    @Schema(description = "Optional fallback base cost")
    private BigDecimal baseCost;

    @Schema(description = "Entity created timestamp")
    private Instant createdAt;

    @Schema(description = "Entity updated timestamp")
    private Instant updatedAt;

    @Schema(description = "Ordered quantity-based cost tiers")
    private List<CostTierDto> tiers;
}
