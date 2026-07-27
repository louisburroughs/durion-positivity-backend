package com.positivity.invoice.service;

import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Creates invoices from sales orders at checkout (order parity story C2, resolved Q1: pos-invoice
 * owns the from-order API shape; the workorder finalization pipeline is not generalized).
 */
public interface OrderInvoiceService {

    /**
     * Create the invoice fronting a sales order, idempotent on {@code orderId}: a replay returns
     * the existing invoice. When {@code workorderId} is present and a workorder invoice already
     * exists, that invoice is returned for tender instead of creating a duplicate (spec R7.2).
     */
    @NonNull
    OrderInvoiceResponse createInvoiceForOrder(@NonNull OrderInvoiceCreationRequest request);

    /**
     * Cancel a DRAFT invoice before any money moved (order-void path, parity story C5, spec
     * R2.4): rejected when the invoice has left DRAFT or carries an authorized/captured payment.
     * Idempotent — cancelling a CANCELLED invoice is a no-op.
     */
    @NonNull
    OrderInvoiceResponse cancelInvoice(@NonNull UUID invoiceId);
}
