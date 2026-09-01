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
 * <p>A refunded invoice payment produces <b>both</b> a {@code RefundRecord} in pos-invoice
 * <b>and</b> a {@code PaymentApplicationReversal} in pos-accounting (ADR-0057 §4), and that
 * reversal reduces {@code collected} in the window the reversal was recorded, on the movement
 * basis above. So a refund of an applied invoice payment does depress {@code collected} — via the
 * reversal, in the reversal's window. Standalone refunds and credit-balance refunds relieve no
 * receivable and are not reflected in {@code collected} at all — but they, and every other
 * completed refund, now have their own dedicated cash-out figure: {@code refunded} (issue #1620).
 * {@code netCashCollected} (= {@code collected - refunded}) mixes bases and under-counts for the
 * commonest refund shape above (see its own field doc); {@code received} minus {@code refunded} is
 * the clean cash-basis pair for "cash in vs cash out".
 *
 * <p>Settlement without new cash — deposit-credit and customer-credit draw-downs — also gets its
 * own figure now: {@code nonCashSettled}, combined with {@code collected} into {@code settled} and
 * {@code settlementRatePct} (issue #1621), so "how much of what we billed got settled by any
 * means" no longer requires reading around the deposit/customer-credit exclusion documented above.
 * And {@code received} (issue #1622) gives cash actually taken in on a pure cash basis, independent
 * of whether it has been applied to an invoice yet.
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
                + " refunded is gross completed refunds (cash out); netCashCollected = collected - refunded"
                + " but mixes bases and under-counts when a refund also reversed an application, so prefer"
                + " received - refunded for a clean cash-in-vs-cash-out pair. received is cash actually"
                + " taken in (cash basis), independent of application. nonCashSettled is invoice"
                + " settlement without new cash (deposit/customer credit draw-downs); settled = collected +"
                + " nonCashSettled and settlementRatePct = settled/invoiced is the \"billed vs settled by"
                + " any means\" pair, distinct from the cash-only collectionRatePct.")
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
                    + " window; 0 when none finalized in the window. Deposit-take invoices — the document a"
                    + " deposit-take order renders for the down payment itself, identified by a non-null"
                    + " depositSourceType — are EXCLUDED: they are a contract-liability document, not a sale"
                    + " (#1623, ADR-0057 decision 6)",
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
                    + " their own dedicated figure, refunded (see #1620): where a refund is accompanied by a"
                    + " payment-application reversal — the commonest shape — that reversal reduces collected"
                    + " in the window the reversal was recorded, per the movement basis above, whereas"
                    + " standalone refunds and credit-balance refunds are not reflected here at all (but do"
                    + " count in refunded). May be NEGATIVE in a heavy-reversal window; it is deliberately"
                    + " not clamped. 0 when nothing was applied or reversed in the window",
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

    @Schema(
            description = "GROSS completed refunds in the window, as a positive number: a genuine cash-out measure"
                    + " (#1620). Sums BOTH refund sources: invoice/standalone refunds replicated from"
                    + " pos-invoice (ExtInvoicePaymentReversal.amount, by reversedAt — includes standalone"
                    + " refunds with no invoice, since cash out is uniform regardless of whether the refund"
                    + " relieves a receivable) AND credit-balance refunds, this module's own"
                    + " CustomerCreditTransaction subledger (type REFUND, by the draw-down's createdAt) —"
                    + " accounting's own cash-out refunds against a customer's credit balance, distinct from"
                    + " and additive with the replica leg since the two are disjoint subledgers. VOID"
                    + " reversals (an authorization released before capture) are EXCLUDED: no cash was ever"
                    + " collected for one, so releasing it is not a cash-out event, and the replica never"
                    + " stores them in the first place. 0 when no refunds were recorded in the window",
            example = "450.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal refunded;

    @Schema(
            description = "collected minus refunded (#1620). WARNING — mixed bases: collected is A/R relief on a"
                    + " movement basis while refunded is cash out, and the commonest refund shape (a refunded"
                    + " invoice payment) produces BOTH a RefundRecord and a PaymentApplicationReversal, so"
                    + " that reversal already reduced collected before this subtraction reduces it again —"
                    + " netCashCollected under-counts for that shape (the credit-balance refund leg has no"
                    + " application reversal, so it is subtracted exactly once). received minus refunded is the clean"
                    + " cash-basis pair for \"how much cash came in vs went out\"; prefer that over this field"
                    + " when answering a cash-in-vs-cash-out question. May be NEGATIVE; not clamped.",
            example = "97800.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal netCashCollected;

    @Schema(
            description = "Sum of ReceivablePayment.totalAmount whose clearedAt falls in the window: cash actually"
                    + " taken in, whether or not it has been applied to an invoice yet (#1622). clearedAt is"
                    + " when the settlement event says cash was actually taken in, not when this replica row"
                    + " was written. Cash received but sitting unapplied appears here and NOT in collected —"
                    + " received and collected are independent populations, like invoiced and collected"
                    + " already are. A voided receivable payment currently retains its clearedAt and"
                    + " totalAmount (there is no VOIDED status on ReceivablePayment), so a void is not"
                    + " deducted from received; voids are rare and refuse once any application exists. 0 when"
                    + " nothing cleared in the window",
            example = "101000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal received;

    @Schema(
            description = "Invoice settlement achieved WITHOUT new cash in the window: deposit-credit draw-downs"
                    + " (ExtInvoiceDepositCreditApplication, replicated from pos-invoice) plus customer-credit"
                    + " APPLICATION draw-downs (this module's own CustomerCreditTransaction subledger), both"
                    + " attributed to the draw-down moment (#1621). The cash behind these was already counted"
                    + " as received in an earlier window, when the deposit or overpayment was originally"
                    + " taken — nonCashSettled never represents new cash. 0 when nothing was drawn down in"
                    + " the window",
            example = "12500.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal nonCashSettled;

    @Schema(
            description = "collected plus nonCashSettled: how much of what was billed got settled in the window by"
                    + " any means, cash or otherwise (#1621). Pairs with settlementRatePct the way invoiced"
                    + " pairs with collectionRatePct, but on a broader settlement basis.",
            example = "110750.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal settled;

    @Schema(
            description = "settled divided by invoiced, times 100, rounded HALF_UP to 2 decimals; null when invoiced"
                    + " is zero, same convention as collectionRatePct (#1621). An invoice settled entirely by"
                    + " deposit credit reaches 100% here while collectionRatePct — deliberately unchanged and"
                    + " cash-only — stays at 0 for that same invoice, since no cash was applied to it — but"
                    + " only holds when both sides land in the same window: nonCashSettled attributes the"
                    + " draw-down to its appliedAt (draft-creation time) while invoiced counts finalizedAt, so"
                    + " that 100% is the typical same-day case, not a guarantee — a draw-down and its"
                    + " invoice's finalization straddling a window boundary land in different windows by"
                    + " design (movement basis). May be NEGATIVE when in-window reversals leave collected"
                    + " negative enough to outweigh nonCashSettled; it is not clamped.",
            example = "88.60",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    @Nullable
    private BigDecimal settlementRatePct;
}
