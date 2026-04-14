package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.InvoiceStatusResponse;
import com.positivity.accounting.internal.dto.PaymentAppliedRequest;
import java.util.UUID;

public interface InvoicePaymentStatusService {

    /**
     * Process a payment applied event and update invoice status.
     * Implements retry logic for transient failures.
     */
    InvoiceStatusResponse processPaymentApplied(PaymentAppliedRequest request);

    /**
     * Get current status of an invoice.
     */
    InvoiceStatusResponse getInvoiceStatus(UUID invoiceId);
}
