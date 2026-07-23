package com.positivity.order.internal.exception;

/**
 * Thrown when tax cannot be computed (pos-tax unreachable, or no jurisdiction address in the
 * location replica). Quote/checkout must never complete with stale or absent tax (spec R3.4).
 */
public class TaxUnavailableException extends RuntimeException {

    public TaxUnavailableException(String message) {
        super(message);
    }
}
