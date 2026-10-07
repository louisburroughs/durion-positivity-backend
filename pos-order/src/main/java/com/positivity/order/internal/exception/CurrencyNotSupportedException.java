package com.positivity.order.internal.exception;

/**
 * A drawer amount stated in an ISO 4217 currency other than pos-order's functional currency (ADR-0067
 * R-1, R-3; CAP:550 S16, #2512): answered 422 {@code CURRENCY_NOT_SUPPORTED}. A missing or non-ISO code is
 * a 400 instead.
 */
public class CurrencyNotSupportedException extends RuntimeException {

    public CurrencyNotSupportedException(String message) {
        super(message);
    }
}
