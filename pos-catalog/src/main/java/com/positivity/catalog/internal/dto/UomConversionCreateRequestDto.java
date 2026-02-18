package com.positivity.catalog.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UomConversionCreateRequestDto {

    @NotBlank
    private String fromUomCode;

    @NotBlank
    private String toUomCode;

    @NotNull
    @Positive
    private BigDecimal conversionFactor;

    private String createdBy;
}
