package com.positivity.accounting.internal.exception;

/**
 * Thrown when the receivable payment named by a cash-application command does not exist (CAP:550
 * S35, #2524). Maps to 404 PAYMENT_NOT_FOUND.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String message) {
        super(message);
    }
}
