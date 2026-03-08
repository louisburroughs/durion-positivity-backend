package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.PaymentOutcomeRequest;
import com.positivity.accounting.internal.dto.PaymentOutcomeResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public service API for processing payment outcomes against invoices.
 * Story #6 — CAP-251.
 */
public interface PaymentOutcomeProcessingService {

    /**
     * Processes a payment outcome and updates the invoice payment status.
     */
    PaymentOutcomeResponse processPaymentOutcome(@NonNull PaymentOutcomeRequest request);

    /**
     * Called when posting retry is exhausted — flags the invoice with a
     * postingError and emits an escalation event.
     */
    void handlePostingRetryExhausted(@NonNull UUID invoiceId, @NonNull String transactionId, int retryCount);
}
