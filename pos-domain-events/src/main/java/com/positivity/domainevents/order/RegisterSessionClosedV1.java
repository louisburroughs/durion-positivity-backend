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
 * <p><b>Schema version 2 (CAP:550 S16, #2512; SPEC-accounting-workspace §4.6, §6.5; AW15, AW16).</b>
 * The fact gains {@link #movements}: one entry per drawer cash movement of the session, with its
 * fixed reason, direction, amount and the reason's detail (category, vendor, bag number, receipt
 * reference), the cashier and the approving manager, so pos-accounting can post each movement and
 * deposits can match a bank drop (S17, S18). {@code openingFloat} is the register's configured float
 * from accounting (AW16) rather than a carried-forward count. The bump is in place on
 * {@code order.events.v1} under ADR-0044 §3 (amendment 2026-09-02; precedent {@code
 * PaymentSettledV1}): the change is additive — no field is removed or retyped and {@code
 * cashMovementTotal} stays — so a consumer built for version 1 reads a version-2 message unchanged.
 * Version-1 messages carry no {@code movements} and deserialise with it {@code null}; a movement
 * recorded before S16 carries no {@code reason}.
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
 * @param movements every cash movement of the session in the order recorded (schema version 2); null
 *     on a version-1 message
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
        @NonNull Instant closedAt,
        @Nullable List<Movement> movements) {

    public static final String EVENT_TYPE = "order.session.closed";
    public static final int SCHEMA_VERSION = 2;

    /**
     * Net settled amount for one tender method over the session.
     *
     * @param methodType CASH / CARD / ON_ACCOUNT / OTHER
     * @param amount net settled (Σ SETTLED − Σ REVERSED) for the method
     */
    public record TenderTotal(
            @NonNull String methodType, @NonNull BigDecimal amount) {}

    /**
     * One drawer cash movement of the closed session (schema version 2).
     *
     * @param movementId the movement
     * @param reason {@code PETTY_EXPENSE}, {@code VENDOR_COD}, {@code BANK_DROP}, {@code
     *     FLOAT_INCREASE} or {@code FLOAT_DECREASE}; null for a movement recorded before the fixed
     *     reasons (its free text is not carried)
     * @param direction {@code IN} or {@code OUT}; theoretical cash adds the amount signed by it
     * @param amount the positive amount moved
     * @param categoryCode the petty-expense category, for {@code PETTY_EXPENSE}
     * @param vendorId the vendor paid, for {@code VENDOR_COD}
     * @param bagNumber the deposit bag, for {@code BANK_DROP}
     * @param receiptReference the receipt, for {@code PETTY_EXPENSE}
     * @param clerkId the cashier who recorded it, from the security context (ADR-0018)
     * @param approvedBy the user id of the manager whose approval token it used, or null
     * @param occurredAt when it was recorded
     */
    public record Movement(
            @NonNull UUID movementId,
            @Nullable String reason,
            @NonNull String direction,
            @NonNull BigDecimal amount,
            @Nullable String categoryCode,
            @Nullable UUID vendorId,
            @Nullable String bagNumber,
            @Nullable String receiptReference,
            @NonNull String clerkId,
            @Nullable String approvedBy,
            @NonNull Instant occurredAt) {

        /** Cash into the drawer. */
        public static final String IN = "IN";

        /** Cash out of the drawer. */
        public static final String OUT = "OUT";
    }
}
