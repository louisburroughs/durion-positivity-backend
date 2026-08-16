package com.positivity.supplier.internal.adapter.ediwheelb3;

/**
 * Thrown when a B3.3 response cannot be read as vendor AR documents (CAP-321 #1227).
 *
 * <p>Every case is a refusal to import money on a guess: an unrecognised document type, an invoice
 * with no number to deduplicate on, an amount with no currency. A vendor invoice that reaches an AP
 * ledger wrong is worse than one that does not arrive, because the second is noticed.
 */
public class InvoiceDecodeException extends RuntimeException {

    public InvoiceDecodeException(String message) {
        super(message);
    }

    public InvoiceDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
