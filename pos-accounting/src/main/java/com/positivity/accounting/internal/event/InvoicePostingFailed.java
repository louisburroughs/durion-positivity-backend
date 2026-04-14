package com.positivity.accounting.internal.event;

import java.util.UUID;

/**
 * Event published when invoice posting retries are exhausted.
 * Story #6 — CAP-251.
 */
public record InvoicePostingFailed(UUID invoiceId, String transactionId, int retryCount) {}
