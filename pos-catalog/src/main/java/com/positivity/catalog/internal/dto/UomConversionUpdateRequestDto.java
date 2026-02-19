package com.positivity.catalog.internal.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UomConversionUpdateRequestDto {

    @NotNull
    @Positive
    private BigDecimal conversionFactor;
}
