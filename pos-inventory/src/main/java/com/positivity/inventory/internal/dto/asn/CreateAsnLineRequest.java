package com.positivity.inventory.internal.dto.asn;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class CreateAsnLineRequest {

    @NotNull
    private UUID poId;

    private UUID poLineId;

    @NotNull
    private String sku;

    @NotNull
    @Positive
    private BigDecimal quantityShipped;

    private String unitOfMeasure;

    private Long unitCostMinor;

    private String lotNumber;
}
