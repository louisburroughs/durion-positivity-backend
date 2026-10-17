package com.positivity.order.internal.client;

import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Synchronous edge to pos-invoice (parity stories C1/C3, replacing the reversal-only
 * {@code BillingPort}). Both operations are money-moving counter-flows that must fail loudly in
 * the request path — the ADR-0044 amendment for pos-order scopes this exception; settlement
 * <em>signals</em> stay asynchronous ({@code payment.events.v1}, resolved Q2).
 */
public interface InvoicingPort {

    /**
     * Create (or replay) the invoice fronting an order at checkout — pos-invoice
     * {@code POST /v1/invoices/from-order}, idempotent on orderId.
     */
    @NonNull
    InvoiceRef createInvoiceForOrder(@NonNull OrderInvoiceCreationRequest request);

    /** Reverse a settled/authorized payment during the cancellation saga (spec R4.6). */
    @NonNull
    PaymentReversalResult reversePayment(
            @NonNull UUID invoiceId, @NonNull UUID paymentIntentId, @NonNull ReversePaymentCommand command);

    /**
     * Cancel the DRAFT invoice fronting an order being voided (parity story C5, spec R2.4) —
     * pos-invoice {@code POST /v1/invoices/{invoiceId}/cancel}. Must fail loudly: a void without
     * the invoice cancelled would leave a live financial document behind.
     */
    void cancelInvoice(@NonNull UUID invoiceId);

    /**
     * Records a register cash tender on the order's invoice (ADR-0067 A8, #2662): pos-invoice creates the CASH
     * intent CAPTURED, checks the rounding triple and publishes the settled fact. Idempotent on the command's key.
     *
     * @throws com.positivity.order.internal.exception.CartIdempotencyConflictException the key was used with another
     *     payload
     * @throws com.positivity.order.internal.exception.OrderConflictException pos-invoice refused the tender (its
     *     balance or its view of the triple disagrees with the register's)
     * @throws com.positivity.order.internal.exception.InvoicingUnavailableException pos-invoice unreachable or failing
     */
    @NonNull
    CashPaymentRef recordCashPayment(@NonNull UUID invoiceId, @NonNull RecordCashPaymentCommand command);
}
