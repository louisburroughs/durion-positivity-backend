package com.positivity.accounting.internal.exception;

/**
 * Thrown when the remainder a caller expects to credit no longer matches the payment's unapplied
 * amount: an apply or an automatic application intervened (CAP:550 S35, #2524; SPEC-accounting-
 * workspace §5.3 item 5). Nothing is written; the client re-reads the payment. Maps to 422
 * PAYMENT_REMAINDER_CHANGED (ADR-0017 §2: the refusal is about the referenced payment's state).
 */
public class PaymentRemainderChangedException extends RuntimeException {

    public PaymentRemainderChangedException(String message) {
        super(message);
    }
}
