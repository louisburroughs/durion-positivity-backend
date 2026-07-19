package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

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

    void voidPayment(@NonNull UUID paymentId);

    void reversePayment(@NonNull UUID paymentId, @NonNull String reason);

    @NonNull
    PaymentApplicationReversal reversePaymentApplication(@NonNull UUID paymentApplicationId, @NonNull String reason);
}
