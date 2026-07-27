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
}
