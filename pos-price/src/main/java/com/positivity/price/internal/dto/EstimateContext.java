package com.positivity.price.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EstimateContext {

    @NotNull
    private UUID estimateId;

    @NotNull
    private UUID customerId;

    @NotNull
    @Size(min = 1)
    private List<@Valid LineItemContext> lineItems;

    @NotNull
    @DecimalMin("0")
    private BigDecimal subtotal;

    @Nullable
    private List<String> appliedPromoCodes;
}
