package com.positivity.order.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesOrderResponse {

    private String orderId;
    private String customerId;
    private String vehicleId;
    private String clerkId;
    private String terminalId;
    private String status;
    private BigDecimal subtotal;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    @Builder.Default
    private List<SalesOrderLineResponse> lines = List.of();
}
