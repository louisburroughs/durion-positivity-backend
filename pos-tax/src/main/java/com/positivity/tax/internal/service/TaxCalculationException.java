package com.positivity.tax.internal.service;

/**
 * Exception thrown when tax calculation fails.
 */
public class TaxCalculationException extends RuntimeException {

    public TaxCalculationException(String message) {
        super(message);
    }

    public TaxCalculationException(String message, Throwable cause) {
        super(message, cause);
    }
}
