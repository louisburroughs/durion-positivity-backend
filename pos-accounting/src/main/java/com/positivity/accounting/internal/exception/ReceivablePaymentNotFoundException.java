package com.positivity.accounting.internal.exception;

/**
 * Thrown when the receivable payment targeted by a manual settlement match
 * cannot be resolved by id (story F1c, issue #963). Maps to 404
 * RECEIVABLE_PAYMENT_NOT_FOUND.
 */
public class ReceivablePaymentNotFoundException extends RuntimeException {

    public ReceivablePaymentNotFoundException(String message) {
        super(message);
    }
}
