package com.positivity.inventory.internal.dto.purchaseorder;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderLineRequest {

    @NotNull
    private Integer lineNumber;

    private UUID skuId;

    @NotNull
    private String description;

    @NotNull
    @Positive
    private BigDecimal quantity;

    @NotNull
    @Positive
    private Long unitCostMinor;

    private String taxCodeId;

    private String glAccountId;
}
