package com.positivity.inventory.internal.dto.asn;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GoodsReceiptLineResponse {

    private UUID receiptLineId;
    private UUID poLineId;
    private String sku;
    private BigDecimal quantityReceived;
    private Long unitCostMinor;
    private Long lineAccruedAmountMinor;
}
