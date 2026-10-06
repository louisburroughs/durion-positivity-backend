package com.positivity.order.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * X-report (mid-day, session still OPEN) or Z-report (close summary) for a register session (parity
 * story G2, spec R6.5). Figures reconcile with the C3 payment-record read model.
 *
 * @param sessionId session identifier
 * @param terminalId terminal
 * @param locationId shop location
 * @param status session status at report time
 * @param reportType X or Z
 * @param openingFloat starting drawer cash
 * @param tenderTotals net settled amount per tender method over the session
 * @param cashSettlements net CASH settled (Σ SETTLED − Σ REVERSED) over the session
 * @param cashMovements signed Σ of cash movements (PAID_IN positive, PAID_OUT negative)
 * @param theoreticalCash openingFloat + cashSettlements + cashMovements
 * @param countedCash physical cash counted at close (null for an X-report / before begin-close)
 * @param overShort countedCash − theoreticalCash (null until counted)
 * @param orderCount number of orders bound to the session
 * @param walkInByClerk walk-in share per cashier over the session's orders that left DRAFT
 * @param movements individual cash movements
 * @param openedAt when the session opened
 * @param generatedAt when the report was produced
 */
public record SessionReport(
        @NonNull UUID sessionId,
        @NonNull String terminalId,
        @Nullable UUID locationId,
        @NonNull String status,
        @NonNull String reportType,
        @NonNull BigDecimal openingFloat,
        @NonNull List<TenderTotal> tenderTotals,
        @NonNull BigDecimal cashSettlements,
        @NonNull BigDecimal cashMovements,
        @NonNull BigDecimal theoreticalCash,
        @Nullable BigDecimal countedCash,
        @Nullable BigDecimal overShort,
        long orderCount,
        @NonNull List<ClerkWalkInShare> walkInByClerk,
        @NonNull List<CashMovementSummary> movements,
        @NonNull Instant openedAt,
        @NonNull Instant generatedAt) {

    /**
     * Net settled amount for one tender method.
     *
     * @param methodType CASH / CARD / ON_ACCOUNT / OTHER
     * @param amount net settled (Σ SETTLED − Σ REVERSED)
     */
    public record TenderTotal(
            @NonNull String methodType, @NonNull BigDecimal amount) {}

    /**
     * One cashier's walk-in share (CAP:550 S8, spec §4.4 item 2): how many of the cashier's orders
     * that left DRAFT were sold to the tenant's CASH house account, and for how much.
     *
     * @param clerkId the order's clerk, as supplied when the cart was created
     * @param orderCount the clerk's session orders that left DRAFT
     * @param walkInOrderCount how many of those are walk-in orders
     * @param walkInTotal Σ grand total of the walk-in orders
     */
    public record ClerkWalkInShare(
            @NonNull String clerkId,
            long orderCount,
            long walkInOrderCount,
            @NonNull BigDecimal walkInTotal) {}
}
