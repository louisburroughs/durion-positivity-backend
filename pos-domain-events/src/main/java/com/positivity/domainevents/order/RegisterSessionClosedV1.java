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
     * @param currencyCode ISO 4217 code of {@code amount} (ADR-0067 R-1): the session's functional currency
     * @param categoryCode the petty-expense category, for {@code PETTY_EXPENSE}
     * @param vendorId the vendor paid, for {@code VENDOR_COD}
     * @param bagNumber the deposit bag, for {@code BANK_DROP}
     * @param receiptReference the receipt, for {@code PETTY_EXPENSE}
     * @param clerkId the cashier who recorded it, from the security context (ADR-0018): the sign-in
     *     name
     * @param clerkUserId the cashier's stable user id, when the sign-in carried one
     * @param approvedBy the user id of the manager whose approval token it used, or null
     * @param occurredAt when it was recorded
     * @param supplierName the supplier on a petty-expense receipt (CAP:550 S32d); CONFIDENTIAL, so it
     *     never appears in {@link #toString()}, an INFO-or-higher log or a metric tag; null when none was
     *     given or on a message produced before S32d
     * @param statedTaxes the tax the receipt states, one entry per indirect-tax regime (S32d, AW31 as
     *     re-confirmed by Order): copied from the receipt, never calculated. The producer always emits a
     *     list, empty for a movement without stated tax; a message produced before S32d reads it null,
     *     which a consumer treats as empty
     * @param supplierRegistrationNumber the supplier's indirect-tax registration number, normalised, kept
     *     by pos-accounting as the claim's evidence (INTERNAL under ADR-0072 Decision 1); stored only after
     *     pos-tax found it well formed, and never in {@link #toString()}
     * @param taxPlausibility {@code PLAUSIBLE} or {@code RATE_UNAVAILABLE}, pos-tax's answer on the stated
     *     amounts; null when no check was made, which a consumer reads as "not checked"
     * @param supplierRegistrationRequired whether the evidence rule asked for the supplier's number; null
     *     when no check was made, never a default {@code false}
     */
    public record Movement(
            @NonNull UUID movementId,
            @Nullable String reason,
            @NonNull String direction,
            @NonNull BigDecimal amount,
            @NonNull String currencyCode,
            @Nullable String categoryCode,
            @Nullable UUID vendorId,
            @Nullable String bagNumber,
            @Nullable String receiptReference,
            @NonNull String clerkId,
            @Nullable UUID clerkUserId,
            @Nullable UUID approvedBy,
            @NonNull Instant occurredAt,
            @Nullable String supplierName,
            @Nullable List<StatedTax> statedTaxes,
            @Nullable String supplierRegistrationNumber,
            @Nullable String taxPlausibility,
            @Nullable Boolean supplierRegistrationRequired) {

        /** Cash into the drawer. */
        public static final String IN = "IN";

        /** Cash out of the drawer. */
        public static final String OUT = "OUT";

        /** pos-tax found the stated amounts plausible. */
        public static final String PLAUSIBLE = "PLAUSIBLE";

        /** pos-tax had no rate to check against, or could not be asked; the posting withholds recovery. */
        public static final String RATE_UNAVAILABLE = "RATE_UNAVAILABLE";

        /**
         * Every component except the supplier's name and number, which are replaced by whether they are
         * present (S32d item 7): a logged fact must never carry either value.
         */
        @Override
        public String toString() {
            return "Movement[movementId=" + movementId
                    + ", reason=" + reason
                    + ", direction=" + direction
                    + ", amount=" + amount
                    + ", currencyCode=" + currencyCode
                    + ", categoryCode=" + categoryCode
                    + ", vendorId=" + vendorId
                    + ", bagNumber=" + bagNumber
                    + ", receiptReference=" + receiptReference
                    + ", clerkId=" + clerkId
                    + ", clerkUserId=" + clerkUserId
                    + ", approvedBy=" + approvedBy
                    + ", occurredAt=" + occurredAt
                    + ", supplierNameProvided=" + (supplierName != null)
                    + ", statedTaxes=" + statedTaxes
                    + ", supplierRegistrationNumberProvided=" + (supplierRegistrationNumber != null)
                    + ", taxPlausibility=" + taxPlausibility
                    + ", supplierRegistrationRequired=" + supplierRegistrationRequired
                    + "]";
        }
    }

    /**
     * One regime's tax as stated on a petty-expense receipt (CAP:550 S32d).
     *
     * @param regime the indirect-tax regime code, as pos-tax's country profile names it
     *     ({@code [A-Z0-9_]{1,32}})
     * @param amount the stated amount, positive, at the currency's exponent
     */
    public record StatedTax(@NonNull String regime, @NonNull BigDecimal amount) {}
}
