package com.positivity.inventory.internal.dto.purchaseorder;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderLineResponse {
    private UUID lineId;
    private Integer lineNumber;
    private UUID skuId;
    private String description;
    private BigDecimal quantityDecimal;
    private Long unitCostMinor;
    private Long lineTotalMinor;
    private Long taxMinor;
    private BigDecimal openQuantityDecimal;
}
