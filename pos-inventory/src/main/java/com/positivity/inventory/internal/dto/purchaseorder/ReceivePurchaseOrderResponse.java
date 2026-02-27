package com.positivity.inventory.internal.dto.purchaseorder;

import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceivePurchaseOrderResponse {
    private UUID poId;
    private PurchaseOrderStatus status;
    private Long openBalanceMinor;
    private String message;
    private List<ReceivePurchaseOrderLineDetail> lines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReceivePurchaseOrderLineDetail {
        private UUID lineId;
        private BigDecimal openQuantityDecimal;
    }
}
