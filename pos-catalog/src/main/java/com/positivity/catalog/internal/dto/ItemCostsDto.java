package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Current item costs")
public class ItemCostsDto {

    @Schema(description = "Item identifier", example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535", requiredMode = REQUIRED)
    private UUID itemId;

    @Schema(description = "Current standard cost", example = "5.00", requiredMode = NOT_REQUIRED)
    private BigDecimal standardCost;

    @Schema(description = "Most recent purchase cost", example = "6.25", requiredMode = NOT_REQUIRED)
    private BigDecimal lastCost;

    @Schema(description = "Weighted average cost", example = "5.75", requiredMode = NOT_REQUIRED)
    private BigDecimal averageCost;
}
