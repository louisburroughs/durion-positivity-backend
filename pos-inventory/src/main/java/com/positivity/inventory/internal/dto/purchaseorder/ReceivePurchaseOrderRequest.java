package com.positivity.inventory.internal.dto.purchaseorder;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceivePurchaseOrderRequest {

    @NotNull
    @NotEmpty
    @Valid
    @JsonAlias("lineReceipts")
    private List<ReceivePurchaseOrderLineRequest> lines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReceivePurchaseOrderLineRequest {

        @NotNull
        private UUID lineId;

        @NotNull
        @Positive
        @JsonAlias("receivedQty")
        private BigDecimal quantityReceived;

        private Long unitCostMinor;
    }
}
