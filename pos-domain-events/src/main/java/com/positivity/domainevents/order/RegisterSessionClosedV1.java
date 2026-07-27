package com.positivity.domainevents.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a register session was closed and its drawer reconciled (odoo-parity plan story G2, issue
 * #1082; spec R6.3–R6.5). The Odoo {@code pos.session} close analog.
 *
 * <p>Published by pos-order on {@code order.events.v1} with
 * {@code eventType = "order.session.closed"} when a session reaches CLOSED. Consumers: pos-accounting
 * posts the over/short variance to GL ({@code REGISTER_OVER_SHORT}, story G3, idempotent on
 * {@code sessionId}) — per-order revenue postings remain authoritative, so this carries only the
 * drawer reconciliation, not a consolidated closing entry.
 *
 * @param sessionId register session identifier (also the envelope aggregateId)
 * @param terminalId terminal the session ran on
 * @param locationId shop location, when set on the session
 * @param openedByClerkId clerk who opened the session
 * @param closedByClerkId clerk who confirmed the close
 * @param openingFloat starting drawer cash
 * @param countedCash physical cash counted at close
 * @param theoreticalCash expected cash (openingFloat + Σ session CASH settlements + Σ movements)
 * @param overShort countedCash − theoreticalCash (positive over, negative short)
 * @param varianceApproved whether an over/short beyond the authorized limit was approved
 * @param currencyCode ISO-4217, USD platform-wide
 * @param tenderTotals net settled amount per tender method across the session's orders
 * @param cashMovementTotal signed Σ of cash movements (PAID_IN positive, PAID_OUT negative)
 * @param openedAt when the session opened
 * @param closedAt when the session reached CLOSED
 */
public record RegisterSessionClosedV1(
        @NonNull UUID sessionId,
        @NonNull String terminalId,
        @Nullable UUID locationId,
        @NonNull String openedByClerkId,
        @Nullable String closedByClerkId,
        @NonNull BigDecimal openingFloat,
        @NonNull BigDecimal countedCash,
        @NonNull BigDecimal theoreticalCash,
        @NonNull BigDecimal overShort,
        boolean varianceApproved,
        @NonNull String currencyCode,
        @NonNull List<TenderTotal> tenderTotals,
        @NonNull BigDecimal cashMovementTotal,
        @NonNull Instant openedAt,
        @NonNull Instant closedAt) {

    public static final String EVENT_TYPE = "order.session.closed";
    public static final int SCHEMA_VERSION = 1;

    /**
     * Net settled amount for one tender method over the session.
     *
     * @param methodType CASH / CARD / ON_ACCOUNT / OTHER
     * @param amount net settled (Σ SETTLED − Σ REVERSED) for the method
     */
    public record TenderTotal(
            @NonNull String methodType, @NonNull BigDecimal amount) {}
}
