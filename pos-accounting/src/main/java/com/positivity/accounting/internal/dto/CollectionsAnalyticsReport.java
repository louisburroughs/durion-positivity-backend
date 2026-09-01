package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Invoiced-vs-collected analytics for one date window (Wave 2 E2, Issue #1590).
 *
 * <p>{@code invoiced} and {@code collected} are deliberately <b>different invoice cohorts</b>:
 * {@code invoiced} sums {@code ExtInvoice.total} for invoices finalized in the window, while {@code
 * collected} sums {@code PaymentApplication.appliedAmount} for cash applications posted in the
 * window, regardless of which invoice — or which period that invoice was finalized in — they
 * settle. {@code collectionRatePct} is therefore a period-level cash-efficiency signal, not a
 * per-cohort collection rate; do not read it as "what fraction of this window's invoiced amount got
 * paid".
 *
 * <p>{@code collected} is <b>payment amounts applied to accounts receivable within the window, net
 * of application reversals recorded within the window — it is not cash received</b>. Reversals are
 * netted on a <b>movement basis</b>: a January payment reversed in March reduces March and never
 * restates January, so a closed period is never rewritten and the measure stays additive (Jan + Feb
 * + Mar equals Jan–Mar). {@code applicationReversals} reports the gross reversal amount so a dip in
 * {@code collected} can be attributed without a second call.
 *
 * <p>Excluded by name: settlement by <b>deposit credit</b> or <b>customer credit</b> (pos-invoice
 * {@code DepositCredit}/{@code DepositCreditApplication}), because that cash was received when the
 * deposit was taken, not when the credit was drawn down — a window in which deposit-funded invoices
 * finalize therefore shows {@code collectionRatePct} understated.
 *
 * <p><b>Refunds have no dedicated figure in this endpoint</b> (pos-invoice {@code RefundRecord}; a
 * dedicated refund measure is tracked as issue #1620) — but they are not invisible here. The
 * commonest shape, a refunded invoice payment, produces <b>both</b> a {@code RefundRecord} in
 * pos-invoice <b>and</b> a {@code PaymentApplicationReversal} in pos-accounting (ADR-0057 §4), and
 * that reversal reduces {@code collected} in the window the reversal was recorded, on the movement
 * basis above. So a refund of an applied invoice payment does depress {@code collected} — via the
 * reversal, in the reversal's window. Standalone refunds and credit-balance refunds relieve no
 * receivable and are not reflected at all.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Invoiced-vs-collected analytics for one date window. invoiced and collected are different"
                + " invoice cohorts (see field descriptions) — do not present collectionRatePct as a"
                + " cohort collection rate. collected is applied-to-A/R net of reversals recorded in the"
                + " window, not cash received: it excludes deposit-credit and customer-credit settlement."
                + " Refunds have no dedicated figure (see #1620), but a refund accompanied by a"
                + " payment-application reversal — the commonest shape — reduces collected in the window"
                + " that reversal was recorded; standalone and credit-balance refunds are not reflected.")
public class CollectionsAnalyticsReport {

    @Schema(description = "Window start date (inclusive)", example = "2026-06-01", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "Window end date (inclusive)", example = "2026-06-30", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Schema(
            description = "Timestamp when the report was generated (ISO 8601)",
            example = "2026-06-30T08:00:00Z",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    @Schema(
            description = "Sum of ExtInvoice.total for invoices whose finalizedAt (accrual/posting date) falls in the"
                    + " window; 0 when none finalized in the window",
            example = "125000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal invoiced;

    @Schema(
            description = "Payment amounts APPLIED to accounts receivable within the window, NET of application"
                    + " reversals recorded within the window — this is NOT cash received. Computed as the"
                    + " sum of PaymentApplication.appliedAmount whose applicationTimestamp falls in the"
                    + " window, minus the sum of PaymentApplicationReversal.amount whose reversedAt falls"
                    + " in the window (movement basis: a January payment reversed in March reduces March"
                    + " and never restates January, so sub-windows remain additive). Settlement by deposit"
                    + " credit or customer credit is EXCLUDED, because that cash was received when the"
                    + " deposit was taken rather than when the credit was drawn down, so a window in which"
                    + " deposit-funded invoices finalize shows collectionRatePct understated. Refunds have"
                    + " no dedicated figure in this endpoint (see #1620): where a refund is accompanied by a"
                    + " payment-application reversal — the commonest shape — that reversal reduces collected"
                    + " in the window the reversal was recorded, per the movement basis above, whereas"
                    + " standalone refunds and credit-balance refunds are not reflected at all. May be"
                    + " NEGATIVE in a heavy-reversal window; it is deliberately not clamped. 0 when nothing"
                    + " was applied or reversed in the window",
            example = "98250.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal collected;

    @Schema(
            description = "GROSS sum of PaymentApplicationReversal.amount whose reversedAt falls in the window, as a"
                    + " positive number; this amount has already been subtracted from collected. Reported"
                    + " so a consumer seeing a dip in collected can attribute it to reversals without a"
                    + " second call. 0 when no reversals were recorded in the window",
            example = "1750.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal applicationReversals;

    @Schema(
            description = "collected divided by invoiced, times 100, rounded HALF_UP to 2 decimals; null when invoiced"
                    + " is zero (the ratio is undefined — never a divide-by-zero error and never a"
                    + " misleading 0). May be NEGATIVE when in-window application reversals exceed in-window"
                    + " applications (see collected); it is not clamped. HALF_UP rounds away from zero for"
                    + " negative values.",
            example = "78.60",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    @Nullable
    private BigDecimal collectionRatePct;
}
