package com.positivity.supplier.internal.invoice.service;

/**
 * Thrown when a window of vendor invoices could not be fetched or read (CAP-321 #1343).
 *
 * <p>Deliberately an exception rather than an empty result. The fetch runs inside a schedule page
 * that advances the checkpoint in the same transaction, so throwing rolls the checkpoint back and
 * the window is re-covered on the next run. Returning nothing would move the checkpoint past
 * invoices that were never fetched, and a missing invoice goes unnoticed until the vendor chases
 * payment.
 */
public class InvoiceFetchException extends RuntimeException {

    public InvoiceFetchException(String message) {
        super(message);
    }

    public InvoiceFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
