package com.positivity.inventory.internal.dto.receiving;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceivingLineResponse {
    private UUID lineId;
    private String productId;
    private BigDecimal expectedQuantity;
    private BigDecimal receivedQuantity;
    private String status;
    private String workorderId;
    private String workorderLineId;
}
