package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "X-report (mid-day) or Z-report (close summary) for a register session")
public record SessionReportResponse(
        UUID sessionId,
        String terminalId,
        UUID locationId,
        String status,
        String reportType,
        BigDecimal openingFloat,
        List<TenderTotal> tenderTotals,
        BigDecimal cashSettlements,
        BigDecimal cashMovements,
        BigDecimal theoreticalCash,
        BigDecimal countedCash,
        BigDecimal overShort,
        long orderCount,
        List<CashMovementResponse> movements,
        Instant openedAt,
        Instant generatedAt) {

    @Schema(description = "Net settled amount for one tender method")
    public record TenderTotal(String methodType, BigDecimal amount) {}

    public static SessionReportResponse from(SessionReport r) {
        return new SessionReportResponse(
                r.sessionId(),
                r.terminalId(),
                r.locationId(),
                r.status(),
                r.reportType(),
                r.openingFloat(),
                r.tenderTotals().stream()
                        .map(t -> new TenderTotal(t.methodType(), t.amount()))
                        .toList(),
                r.cashSettlements(),
                r.cashMovements(),
                r.theoreticalCash(),
                r.countedCash(),
                r.overShort(),
                r.orderCount(),
                r.movements().stream().map(CashMovementResponse::from).toList(),
                r.openedAt(),
                r.generatedAt());
    }
}
