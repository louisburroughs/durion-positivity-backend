package com.positivity.inventory.internal.dto.asn;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class CreateGoodsReceiptLineRequest {

    private UUID poLineId;

    @NotNull
    private String sku;

    @NotNull
    @Positive
    @Digits(integer = 10, fraction = 0)
    private BigDecimal quantityReceived;

    @NotNull
    private Long unitCostMinor;

    private String lotNumber;
}
