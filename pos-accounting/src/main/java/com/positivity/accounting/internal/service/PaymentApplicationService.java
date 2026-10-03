package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.PaymentApplicationReversalResponse;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PaymentApplicationService {

    @NonNull
    ReceivablePayment handlePaymentCleared(
            @NonNull UUID paymentId,
            @NonNull UUID customerId,
            @NonNull String currency,
            @NonNull BigDecimal totalAmount,
            @NonNull Instant clearedAt,
            @NonNull UUID sourceEventId);

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
     * @param creditRequestId idempotency key for the credit leg
     * @return the credit issued, or {@code null} when the payment had nothing left to credit
     */
    PaymentApplicationResponse.@Nullable CustomerCreditInfo creditUnappliedPayment(
            @NonNull UUID paymentId, @NonNull String creditRequestId);

    void voidPayment(@NonNull UUID paymentId);

    void reversePayment(@NonNull UUID paymentId, @NonNull String reason);

    @NonNull
    PaymentApplicationReversalResponse reversePaymentApplication(
            @NonNull UUID paymentApplicationId, @NonNull String reason);
}
