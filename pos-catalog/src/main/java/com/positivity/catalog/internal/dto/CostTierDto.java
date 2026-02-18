package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "Supplier item cost tier")
public class CostTierDto {

    @Schema(description = "Inclusive minimum quantity", example = "1")
    private Integer minQuantity;

    @Schema(description = "Inclusive maximum quantity; null means and above", example = "10", nullable = true)
    private Integer maxQuantity;

    @Schema(description = "Per-unit cost for this quantity range", example = "5.00")
    private BigDecimal unitCost;
}
