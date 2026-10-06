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

        @Schema(description = "Walk-in share per cashier over the session's orders that left DRAFT")
        List<ClerkWalkInShare> walkInByClerk,

        List<CashMovementResponse> movements,
        Instant openedAt,
        Instant generatedAt) {

    @Schema(description = "Net settled amount for one tender method")
    public record TenderTotal(String methodType, BigDecimal amount) {}

    @Schema(description = "One cashier's walk-in share: orders sold to the Walk-in customer out of all their orders")
    public record ClerkWalkInShare(
            @Schema(description = "Clerk the orders belong to", example = "clerk-001")
            String clerkId,

            @Schema(description = "The clerk's session orders that left DRAFT", example = "3")
            long orderCount,

            @Schema(description = "How many of those were sold to the Walk-in customer", example = "1")
            long walkInOrderCount,

            @Schema(description = "Total of the clerk's walk-in orders", example = "84.37")
            BigDecimal walkInTotal) {}

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
                r.walkInByClerk().stream()
                        .map(c -> new ClerkWalkInShare(
                                c.clerkId(), c.orderCount(), c.walkInOrderCount(), c.walkInTotal()))
                        .toList(),
                r.movements().stream().map(CashMovementResponse::from).toList(),
                r.openedAt(),
                r.generatedAt());
    }
}
