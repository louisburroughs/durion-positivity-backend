package com.positivity.invoice.service;

import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceResponse;
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
}
