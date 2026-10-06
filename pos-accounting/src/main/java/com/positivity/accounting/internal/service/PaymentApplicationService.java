package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.PaymentApplicationReversalResponse;
import com.positivity.accounting.internal.dto.RemainderCreditRequest;
import com.positivity.accounting.internal.dto.RemainderCreditResponse;
import com.positivity.accounting.internal.entity.ReceivablePayment;
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
     * Convert a payment's whole unapplied balance into a {@code CustomerCredit} (AD-003) when no
     * invoice can take it, e.g. the invoice it was meant for is already paid in full (#2435). The
     * credit-issuance GL posting (Dr Undeposited Funds / Cr Customer Credit Liability) is enqueued in
     * the same transaction, keyed on {@code creditRequestId}; a replay with the same key changes
     * nothing.
     *
     * @param paymentId an {@code AVAILABLE} receivable payment
     * @param creditRequestId idempotency key for the credit leg; the credit records it (#2524), so a
     *                        replay returns the credit it issued
     * @return the credit issued (or issued earlier under this key), or {@code null} when the payment
     *         had nothing left to credit
     */
    PaymentApplicationResponse.@Nullable CustomerCreditInfo creditUnappliedPayment(
            @NonNull UUID paymentId, @NonNull String creditRequestId);

    /**
     * Keep a payment's whole unapplied remainder as a customer credit on request (AD-003; CAP:550 S35,
     * #2524). Idempotent on {@code requestId}; refused, writing nothing, when the payment is not
     * {@code AVAILABLE}, is in another currency, or no longer carries exactly
     * {@code expectedAmount} unapplied.
     *
     * @param paymentId the payment whose remainder to credit
     * @param request   idempotency key and the remainder the caller expects
     * @return the credit issued, or the one issued earlier under the same key
     */
    @NonNull
    RemainderCreditResponse creditPaymentRemainder(@NonNull UUID paymentId, @NonNull RemainderCreditRequest request);

    void voidPayment(@NonNull UUID paymentId);

    void reversePayment(@NonNull UUID paymentId, @NonNull String reason);

    @NonNull
    PaymentApplicationReversalResponse reversePaymentApplication(
            @NonNull UUID paymentApplicationId, @NonNull String reason);
}
