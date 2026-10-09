package com.positivity.order.internal.exception;

/**
 * pos-tax's plausibility check could not be relied on for a petty expense that carries the supplier's number (CAP:550
 * S32d item 6, amendment A4): pos-tax was unreachable, timed out, failed, or answered something pos-order's replicas
 * disagree with. Answered 503 {@code TAX_CHECK_UNAVAILABLE} with {@code Retry-After}; nothing is recorded, and the
 * register may retry or record without the number under the same {@code requestId}. The message is value-free.
 */
public class TaxCheckUnavailableException extends RuntimeException {

    public TaxCheckUnavailableException(String message) {
        super(message);
    }
}
