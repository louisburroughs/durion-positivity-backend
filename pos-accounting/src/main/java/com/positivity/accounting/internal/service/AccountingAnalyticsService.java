package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.CollectionsAnalyticsReport;
import com.positivity.accounting.internal.dto.PaymentLagCohortsReport;
import com.positivity.accounting.internal.dto.VendorSpendReport;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;

/**
 * Wave 2 read-only accounting analytics (Issue #1590 E2, Issue #1591 E3, Issue #1596 E8).
 *
 * <p>All three endpoints are single-window aggregates over data pos-accounting already
 * persists — the {@code ExtInvoice} replica (fed from {@code invoice.events.v1}) and this
 * module's own {@code PaymentApplication}/{@code APPayment}/{@code VendorBill} records. No
 * schema changes; no new replica field.
 */
public interface AccountingAnalyticsService {

    /**
     * Invoiced-vs-collected analytics for one date window (Issue #1590, E2).
     *
     * <p><b>invoiced</b> sums {@code ExtInvoice.total} for invoices whose {@code finalizedAt}
     * (accrual/posting date) falls in the inclusive window — the same anchor {@code
     * generateTaxLiability} and {@code ExtInvoiceRepository#findByFinalizedAtBetween} already use for
     * "invoice date" elsewhere in this module, and the semantically correct one: {@code
     * invoiceCreatedAt} is only the draft-creation timestamp, not yet a real financial event. A still-
     * {@code DRAFT} invoice (null {@code finalizedAt}) never contributes.
     *
     * <p><b>collected</b> sums {@code PaymentApplication.appliedAmount} for applications whose {@code
     * applicationTimestamp} falls in the same inclusive window — settled cash only. {@code
     * PaymentIntent}/{@code Receipt} (pos-invoice pre-settlement artifacts) are never consulted: an
     * intent is not a collection.
     *
     * <p>These are deliberately different invoice cohorts: the invoice finalized in this window and
     * the invoice whose payment settled in this window can be entirely different invoices. {@code
     * collectionRatePct} is a period-level cash-efficiency signal, not a per-cohort collection rate.
     *
     * @param startDate window start date (inclusive)
     * @param endDate   window end date (inclusive)
     * @return single-row collections analytics; {@code collectionRatePct} is {@code null} when {@code
     *         invoiced} is zero (undefined ratio — never a divide-by-zero exception, never a
     *         misleading {@code 0})
     * @throws IllegalArgumentException if {@code endDate} is before {@code startDate}
     */
    @NonNull
    CollectionsAnalyticsReport getCollectionsAnalytics(@NonNull LocalDate startDate, @NonNull LocalDate endDate);

    /**
     * Payment-lag cohorts for invoices issued in one date window (Issue #1591, E3).
     *
     * <p>Params are anchored by invoice <b>issue date</b> ({@code ExtInvoice.finalizedAt} — reliably
     * populated by {@code InvoiceEventsListener} whenever pos-invoice has finalized the invoice, the
     * same field the tax-liability report already treats as the invoice's effective date; {@code
     * invoiceCreatedAt} is only the draft timestamp and is not used), not payment date: the cohort
     * bucket is a property of invoices <i>issued</i> in the window, whatever happened to them since.
     * A still-{@code DRAFT} invoice (null {@code finalizedAt}) has not been issued and never
     * contributes.
     *
     * <p><b>Aged receivables is not a precedent for this anchor.</b> {@code generateAgedReceivables}
     * ages from the invoice's <b>due date</b> (falling back to a document date of {@code
     * invoiceCreatedAt} → {@code finalizedAt} → {@code updatedAt}, which is also what its
     * existence filter tests), so its effective date for aging is neither this anchor nor the
     * tax-liability report's.
     *
     * <p><b>Lag</b> is the whole days between that issue-date anchor and the {@code
     * PaymentApplication.applicationTimestamp} at which {@code invoiceBalanceAfter} first reaches
     * zero (applications for an invoice are ordered by {@code applicationTimestamp}, then {@code
     * paymentApplicationId} as a deterministic tiebreak). Boundaries are inclusive at the upper edge:
     * {@code lag <= 30} to {@code <=30}, {@code 31 <= lag <= 60} to {@code 31-60}, {@code 61 <= lag <=
     * 90} to {@code 61-90} — mirroring this module's existing 0-30/31-60/61-90 aging-bucket
     * convention (see {@code generateAgedReceivables}).
     *
     * <p><b>unpaid</b> is a real cohort, not an omission: an invoice with no application at all, one
     * whose applications never bring {@code invoiceBalanceAfter} to zero within the observed data
     * (partially applied — deterministically stays in {@code unpaid} until it is fully paid, per its
     * own {@code invoiceBalanceAfter} history, however many periods that takes), and one whose lag
     * exceeds 90 days (there is no fifth "90+" bucket; a very slow full payment is grouped with never-
     * paid because both represent invoices that missed the 90-day collection window) all land here.
     * Every cohort's {@code amount} is the invoice's full total, not a partial or remaining balance.
     *
     * @param issuedFrom window start date (inclusive), anchored on invoice {@code finalizedAt}
     * @param issuedTo   window end date (inclusive)
     * @param limit      maximum cohort rows to return, taken from the fixed {@code <=30, 31-60, 61-90,
     *                   unpaid} order; {@code <= 0} defaults to 4 (all cohorts), values above 4 are
     *                   capped at 4
     * @return cohort rows in fixed order, truncated to {@code limit}
     * @throws IllegalArgumentException if {@code issuedTo} is before {@code issuedFrom}
     */
    @NonNull
    PaymentLagCohortsReport getPaymentLagCohorts(@NonNull LocalDate issuedFrom, @NonNull LocalDate issuedTo, int limit);

    /**
     * Per-vendor spend analytics for one date window (Issue #1596, E8).
     *
     * <p><b>paidAmount</b> sums {@code APPayment.grossAmount} — the amount allocated against
     * vendor bills, before any processor fee — for payments to that vendor whose {@code
     * paymentDate} falls in the inclusive window and whose {@code status} shows the gateway
     * already moved the cash ({@code GATEWAY_SUCCEEDED} or later: {@code GL_POST_PENDING},
     * {@code GL_POSTED}, {@code GL_POST_FAILED} all still count, since a GL-posting failure never
     * un-does a gateway-confirmed payment). A payment stuck in {@code INITIATED}, {@code
     * GATEWAY_PENDING} or {@code GATEWAY_FAILED} moved no cash and is excluded.
     *
     * <p><b>billCount</b> and <b>avgBillAmount</b> are a DIFFERENT population: every {@code
     * VendorBill} for that vendor whose {@code billDate} falls in the same window, regardless of
     * status. {@code avgBillAmount} is {@code sum(totalAmount) / billCount}, and is {@code 0}
     * (never {@code null}) when {@code billCount} is {@code 0}.
     *
     * <p>Callers must not assume {@code avgBillAmount * billCount} reconciles to {@code
     * paidAmount} — see {@link com.positivity.accounting.internal.dto.VendorSpendRow} Javadoc.
     *
     * <p>Vendor {@code name} is resolved server-side from the AP vendor directory, falling back
     * to the vendor-name snapshot recorded on the vendor's own bills/payments when the vendor has
     * no directory entry. Rows are ordered by {@code paidAmount} descending and capped at {@code
     * limit}, so the top-N-by-spend read needs no paging.
     *
     * @param startDate window start date (inclusive)
     * @param endDate   window end date (inclusive)
     * @param limit     maximum vendor rows to return, top-by-paidAmount; {@code <= 0} is a client
     *                  error, values above the module cap are clamped
     * @return vendor spend rows, {@code paidAmount} descending, bounded to {@code limit}
     * @throws IllegalArgumentException if {@code endDate} is before {@code startDate}, or {@code
     *                                  limit} is not positive
     */
    @NonNull
    VendorSpendReport getVendorSpend(@NonNull LocalDate startDate, @NonNull LocalDate endDate, int limit);
}
