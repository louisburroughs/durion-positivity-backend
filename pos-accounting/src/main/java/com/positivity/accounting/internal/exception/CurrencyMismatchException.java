package com.positivity.accounting.internal.exception;

/**
 * Thrown when a payment is applied to an invoice in a different currency (issue #2310, ADR-0067
 * DF-2; story #114: "Currency mismatch: reject; no partial writes"). The application is refused
 * before any amount moves. Maps to 409 CURRENCY_MISMATCH.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String message) {
        super(message);
    }
}
