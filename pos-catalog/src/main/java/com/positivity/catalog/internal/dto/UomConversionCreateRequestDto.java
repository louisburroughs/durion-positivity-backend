package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Request to create a unit-of-measure conversion")
public class UomConversionCreateRequestDto {

    @NotBlank
    @Schema(description = "Source unit-of-measure code", example = "CASE", requiredMode = REQUIRED)
    private String fromUomCode;

    @NotBlank
    @Schema(description = "Target unit-of-measure code", example = "EACH", requiredMode = REQUIRED)
    private String toUomCode;

    @NotNull
    @Positive
    @Schema(
            description = "Multiplier to convert one unit of the source UOM into the target UOM",
            example = "12",
            requiredMode = REQUIRED)
    private BigDecimal conversionFactor;

    @Schema(description = "Identifier of the user creating the conversion", example = "system", requiredMode = NOT_REQUIRED)
    private String createdBy;
}
