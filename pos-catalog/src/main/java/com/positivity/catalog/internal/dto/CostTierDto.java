package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "Supplier item cost tier")
public class CostTierDto {

    @NotNull
    @Min(1)
    @Schema(description = "Inclusive minimum quantity", example = "1", requiredMode = REQUIRED)
    private Integer minQuantity;

    @Schema(
            description = "Inclusive maximum quantity; null means and above",
            example = "10",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private Integer maxQuantity;

    @NotNull
    @Positive(message = "must be positive.")
    @Schema(description = "Per-unit cost for this quantity range", example = "5.00", requiredMode = REQUIRED)
    private BigDecimal unitCost;
}
