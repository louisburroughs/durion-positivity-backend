package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Current item costs")
public class ItemCostsDto {

    private UUID itemId;

    private BigDecimal standardCost;

    private BigDecimal lastCost;

    private BigDecimal averageCost;
}
