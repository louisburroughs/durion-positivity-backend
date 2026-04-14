package com.positivity.invoice.service;

import com.positivity.invoice.internal.dto.InitiatePaymentRequest;
import com.positivity.invoice.internal.dto.InitiatePaymentResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public service interface for payment initiation and capture operations.
 *
 * <p>
 * Implementations handle permission enforcement, idempotency, and
 * payment gateway adapter delegation. This interface is the only exposed
 * API for cross-module access (ADR-0026).
 *
 * Story #9.
 */
public interface PaymentService {

    /**
     * Initiates a card payment against an invoice (sale-capture or auth-only).
     *
     * @param invoiceId the invoice to pay
     * @param request   payment initiation parameters including flow and token
     * @return the created payment intent with status and amounts
     */
    @NonNull
    InitiatePaymentResponse initiatePayment(@NonNull UUID invoiceId, @NonNull InitiatePaymentRequest request);

    /**
     * Explicitly captures an authorized payment hold.
     *
     * @param invoiceId       the invoice the payment belongs to
     * @param paymentIntentId the authorization to capture
     * @param amount          the amount to capture (may be partial)
     * @return the updated payment intent with CAPTURED status and amounts
     */
    @NonNull
    InitiatePaymentResponse capturePayment(
            @NonNull UUID invoiceId,
            @NonNull UUID paymentIntentId,
            @NonNull BigDecimal amount,
            @NonNull String captureIdempotencyKey);
}
