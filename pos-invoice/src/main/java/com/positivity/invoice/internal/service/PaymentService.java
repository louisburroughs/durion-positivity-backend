package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.InitiatePaymentRequest;
import com.positivity.invoice.internal.dto.InitiatePaymentResponse;
import com.positivity.invoice.internal.dto.PaymentIntentResponse;
import java.math.BigDecimal;
import java.util.List;
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

    /**
     * Lists every payment intent raised against an invoice (#2226, #2215).
     *
     * @param invoiceId the invoice to list payments for
     * @return the invoice's payment intents
     */
    @NonNull
    List<PaymentIntentResponse> listInvoicePayments(@NonNull UUID invoiceId);

    /**
     * Reads a single payment intent's detail (#2226, #2215).
     *
     * @param invoiceId the invoice the payment intent must belong to
     * @param paymentId the payment intent to read
     * @return the payment intent's detail, including the refundable balance
     */
    @NonNull
    PaymentIntentResponse getInvoicePayment(@NonNull UUID invoiceId, @NonNull UUID paymentId);
}
