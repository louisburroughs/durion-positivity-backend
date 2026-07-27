package com.positivity.order.internal.exception;

/**
 * pos-invoice could not create the invoice fronting a checkout (parity story C1). Checkout is
 * atomic from the client's view: this exception rolls the PENDING_PAYMENT transition back and maps
 * to a 503 {@code ORDER_INVOICING_UNAVAILABLE} ApiError.
 */
public class InvoicingUnavailableException extends RuntimeException {

    public InvoicingUnavailableException(String message) {
        super(message);
    }

    public InvoicingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
