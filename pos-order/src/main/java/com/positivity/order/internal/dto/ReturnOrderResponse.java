package com.positivity.order.internal.dto;

import com.positivity.order.service.model.ReturnOrderSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A return order and its lines")
public record ReturnOrderResponse(
        UUID returnOrderId,
        UUID originalOrderId,
        String originalOrderNumber,
        UUID locationId,
        UUID customerId,
        String refundMethod,
        String status,
        String reasonCode,
        BigDecimal totalRefund,
        String failureReason,
        List<Line> lines,
        Instant returnedAt,
        Instant createdAt) {

    @Schema(description = "One returned line")
    public record Line(
            UUID returnLineId,
            UUID originalOrderLineId,
            String itemSku,
            int returnQty,
            String condition,
            BigDecimal lineRefund,
            List<String> serialNumbers) {}

    public static ReturnOrderResponse from(ReturnOrderSummary s) {
        return new ReturnOrderResponse(
                s.returnOrderId(),
                s.originalOrderId(),
                s.originalOrderNumber(),
                s.locationId(),
                s.customerId(),
                s.refundMethod(),
                s.status(),
                s.reasonCode(),
                s.totalRefund(),
                s.failureReason(),
                s.lines().stream()
                        .map(l -> new Line(
                                l.returnLineId(),
                                l.originalOrderLineId(),
                                l.itemSku(),
                                l.returnQty(),
                                l.condition(),
                                l.lineRefund(),
                                l.serialNumbers()))
                        .toList(),
                s.returnedAt(),
                s.createdAt());
    }
}
