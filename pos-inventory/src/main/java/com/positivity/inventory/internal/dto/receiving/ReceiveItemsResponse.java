package com.positivity.inventory.internal.dto.receiving;

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
public class ReceiveItemsResponse {
    private UUID sessionId;
    private String sessionStatus;
    private int linesProcessed;
    private List<VarianceSummaryResponse> variances;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VarianceSummaryResponse {
        private UUID lineId;
        private String productId;
        private String varianceType;
        private BigDecimal varianceQuantity;
        private BigDecimal expectedQuantity;
        private BigDecimal receivedQuantity;
    }
}
