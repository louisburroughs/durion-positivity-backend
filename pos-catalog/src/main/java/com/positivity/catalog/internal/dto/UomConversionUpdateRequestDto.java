package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Request to update a unit-of-measure conversion factor")
public class UomConversionUpdateRequestDto {

    @NotNull
    @Positive
    @Schema(
            description = "Multiplier to convert one unit of the source UOM into the target UOM",
            example = "12",
            requiredMode = REQUIRED)
    private BigDecimal conversionFactor;
}
