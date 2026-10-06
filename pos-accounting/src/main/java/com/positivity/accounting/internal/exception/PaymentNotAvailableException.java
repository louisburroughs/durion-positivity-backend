package com.positivity.accounting.internal.exception;

/**
 * Thrown when a cash-application command targets a receivable payment that is not {@code AVAILABLE}
 * (already fully applied or credited; CAP:550 S35, #2524). The conflict is with the payment's
 * current state, so it maps to 409 PAYMENT_NOT_AVAILABLE.
 */
public class PaymentNotAvailableException extends RuntimeException {

    public PaymentNotAvailableException(String message) {
        super(message);
    }
}
