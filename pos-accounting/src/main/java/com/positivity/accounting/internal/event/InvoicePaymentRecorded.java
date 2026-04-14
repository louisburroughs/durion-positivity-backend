package com.positivity.accounting.internal.event;

import java.util.UUID;

/**
 * Event published when an invoice payment is recorded successfully.
 * Story #6 — CAP-251.
 */
public record InvoicePaymentRecorded(UUID invoiceId, String transactionId, long amountMinor) {}
