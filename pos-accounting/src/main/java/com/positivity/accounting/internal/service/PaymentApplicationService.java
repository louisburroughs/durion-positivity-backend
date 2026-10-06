package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.PaymentApplicationReversalResponse;
import com.positivity.accounting.internal.dto.RemainderCreditRequest;
import com.positivity.accounting.internal.dto.RemainderCreditResponse;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.enums.ApplicationSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PaymentApplicationService {

    /**
     * Record a cleared customer payment as an {@code AVAILABLE} receivable payment. Idempotent on
     * {@code sourceEventId}; a payment already recorded by another path keeps its values, including
     * the remittance fields (the first writer wins, #2502).
     *
     * @param sourceInvoiceId invoice the payment was taken against, when the recording fact names one
     * @param paymentMethod   settlement method as the fact sends it ({@code CASH}, {@code CARD},
     *                        {@code ON_ACCOUNT}, {@code OTHER}), when the path carries one
     */
    @NonNull
    ReceivablePayment handlePaymentCleared(
            @NonNull UUID paymentId,
            @NonNull UUID customerId,
            @NonNull String currency,
            @NonNull BigDecimal totalAmount,
            @NonNull Instant clearedAt,
            @NonNull UUID sourceEventId,
            @Nullable UUID sourceInvoiceId,
            @Nullable String paymentMethod);

    /**
     * Apply a payment across the requested invoices.
     *
     * <p>Allocation ordering is governed by the request's optional
     * {@code allocationStrategy}; implementations must resolve the effective strategy via
     * {@link PaymentApplicationRequest#resolveAllocationStrategy()} so an absent value
     * defaults to {@code CALLER_ORDER} (behavior identical to requests predating the field),
     * while {@code OLDEST_FIRST} allocates by ascending invoice date (Issue #955).
     *
     * @param paymentId payment to apply
     * @param request   invoices, amounts, idempotency key, and optional allocation strategy
     * @return application response with per-invoice application details
     */
    @NonNull
    PaymentApplicationResponse applyPaymentToInvoices(
            @NonNull UUID paymentId, @NonNull PaymentApplicationRequest request);

    /**
     * Apply a payment to one invoice on behalf of the system, not a person (CAP:550 S2, #2503): the
     * apply logic of {@link #applyPaymentToInvoices} — idempotent on {@code requestId}, capped at the
     * invoice's balance with any excess kept as a {@code CustomerCredit} (AD-003), except on the CASH
     * walk-in account, where the excess stays unapplied and is raised (#2508) — with the
     * application, the credit and both GL work items dated {@code appliedAt}, the system actor
     * ({@code SYSTEM}, ADR-0018) as creator, and {@code source} recorded on the application. Internal
     * only: the REST command keeps stamping the current time (§4.4 item 5).
     *
     * @param paymentId an {@code AVAILABLE} receivable payment
     * @param invoiceId the one invoice to apply it to
     * @param amount    the amount to apply before the cap, usually the payment's whole unapplied amount
     * @param requestId the idempotency key; a replay returns the recorded result and writes nothing
     * @param appliedAt the application date, e.g. the settlement instant
     * @param source    the path applying it; never {@link ApplicationSource#MANUAL}
     * @return the application result, or the recorded one on a replay
     */
    @NonNull
    PaymentApplicationResponse applyAutomatically(
            @NonNull UUID paymentId,
            @NonNull UUID invoiceId,
            @NonNull BigDecimal amount,
            @NonNull String requestId,
            @NonNull Instant appliedAt,
            @NonNull ApplicationSource source);

    /**
     * Convert a payment's whole unapplied balance into a {@code CustomerCredit} (AD-003) when no
     * invoice can take it, e.g. the invoice it was meant for is already paid in full (#2435). The
     * credit-issuance GL posting (Dr Undeposited Funds / Cr Customer Credit Liability) is enqueued in
     * the same transaction, keyed on {@code creditRequestId}; a replay with the same key changes
     * nothing.
     *
     * @param paymentId an {@code AVAILABLE} receivable payment
     * @param creditRequestId idempotency key for the credit leg; the credit records it (#2524), so a
     *                        replay returns the credit it issued
     * <p>The CASH walk-in account never gets a credit (#2508, §4.4 item 4): its remainder stays unapplied
     * on the payment, raised as a walk-in overpayment, and this returns {@code null}.
     *
     * @return the credit issued (or issued earlier under this key), or {@code null} when the payment
     *         had nothing left to credit or is the CASH walk-in account's
     */
    PaymentApplicationResponse.@Nullable CustomerCreditInfo creditUnappliedPayment(
            @NonNull UUID paymentId, @NonNull String creditRequestId);

    /**
     * Keep a payment's whole unapplied remainder as a customer credit on request (AD-003; CAP:550 S35,
     * #2524). Idempotent on {@code requestId}; refused, writing nothing, when the payment is not
     * {@code AVAILABLE}, is in another currency, or no longer carries exactly
     * {@code expectedAmount} unapplied, and refused for a payment of the CASH walk-in account
     * ({@code CashCustomerCreditNotAllowedException}, 422; #2508).
     *
     * @param paymentId the payment whose remainder to credit
     * @param request   idempotency key and the remainder the caller expects
     * @return the credit issued, or the one issued earlier under the same key
     */
    @NonNull
    RemainderCreditResponse creditPaymentRemainder(@NonNull UUID paymentId, @NonNull RemainderCreditRequest request);

    /**
     * A completed refund of a payment takes its money out of what can still be applied (#2508): the
     * payment's unapplied remainder shrinks by the refunded amount, never below zero, and the payment
     * becomes {@code FULLY_APPLIED} when nothing is left. Applications already made are never touched.
     * This is how a CASH walk-in excess, refunded through pos-invoice, leaves the unpaid walk-in sales read.
     *
     * <p>Runs in the caller's transaction (the refund replica's handler, whose {@code refundId} key makes
     * it once per refund). A payment accounting has not recorded yet is left to its settlement ({@link
     * #releaseRefundsRecordedBeforeSettlement}) and raised at WARN; a refund above the unapplied remainder
     * releases the remainder only and is raised at WARN ({@code RefundReleaseAlert}, #2556).
     *
     * @param paymentId      the refunded payment ({@code paymentIntentId})
     * @param refundedAmount the refunded amount, above zero
     * @param refundId       the refund, for the log
     * @return the amount taken off the unapplied remainder; zero when none was
     */
    @NonNull
    BigDecimal releaseRefundedRemainder(
            @NonNull UUID paymentId, @NonNull BigDecimal refundedAmount, @NonNull UUID refundId);

    /**
     * The other half of {@link #releaseRefundedRemainder} for a refund processed before its settlement fact
     * (#2556): when the settlement records the payment, the refunds already stored for it come off what the
     * automatic application left unapplied, never below zero, with any excess raised at WARN. Called after
     * the automatic application in the settlement's transaction, so the outcome is the one the settlement
     * then refund order gives.
     *
     * <p>Only the settlement that recorded the payment releases anything ({@code sourceEventId} equals
     * {@code settlementEventId}): a payment recorded earlier was there for every later refund, which
     * released itself, and a settlement replayed under a new event id changes nothing.
     *
     * @param paymentId         the settled payment ({@code paymentIntentId})
     * @param settlementEventId the settlement fact's event id
     * @return the amount taken off the unapplied remainder; zero when none was
     */
    @NonNull
    BigDecimal releaseRefundsRecordedBeforeSettlement(@NonNull UUID paymentId, @NonNull UUID settlementEventId);

    void voidPayment(@NonNull UUID paymentId);

    void reversePayment(@NonNull UUID paymentId, @NonNull String reason);

    @NonNull
    PaymentApplicationReversalResponse reversePaymentApplication(
            @NonNull UUID paymentApplicationId, @NonNull String reason);
}
