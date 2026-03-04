package com.positivity.price.internal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LineItemContext {

    @NotBlank
    private String sku;

    @NotNull
    @DecimalMin("0")
    private BigDecimal quantity;

    @NotNull
    @DecimalMin("0")
    private BigDecimal unitPrice;
}
