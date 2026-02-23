package com.positivity.accounting.service;

import java.util.UUID;

import com.positivity.accounting.internal.dto.InvoiceStatusResponse;
import com.positivity.accounting.internal.dto.PaymentAppliedRequest;

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