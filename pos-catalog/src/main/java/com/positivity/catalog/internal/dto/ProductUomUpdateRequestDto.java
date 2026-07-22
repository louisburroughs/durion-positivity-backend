package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.ProductUomType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Request to update a product UoM conversion (the UoM code is immutable)")
public class ProductUomUpdateRequestDto {

    @Schema(
            description = "New role of this UoM for the product; unchanged when omitted",
            example = "PACK",
            implementation = ProductUomType.class,
            requiredMode = NOT_REQUIRED)
    private ProductUomType uomType;

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @Schema(
            description = "Multiplier converting one unit of this UoM into the product's base UoM",
            example = "12",
            requiredMode = REQUIRED)
    private BigDecimal factorToBase;

    @NotNull
    @Min(0)
    @Max(6)
    @Schema(
            description = "Decimal precision scale for quantities expressed in this UoM (0-6)",
            example = "0",
            requiredMode = REQUIRED)
    private Integer precisionScale;
}
