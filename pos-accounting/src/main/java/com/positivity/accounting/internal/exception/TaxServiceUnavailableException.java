package com.positivity.accounting.internal.exception;

import org.jspecify.annotations.NonNull;

/**
 * pos-tax could not take a tax-registration write (CAP:550 S32c): it is unreachable, answered 5xx, or refused this
 * front door's secret. 503 {@code SERVICE_UNAVAILABLE} with {@code Retry-After}; nothing is stored. The message never
 * carries a registration number or a secret.
 */
public class TaxServiceUnavailableException extends RuntimeException {

    public static final String CODE = "SERVICE_UNAVAILABLE";

    /** Seconds the caller should wait before retrying. */
    public static final int RETRY_AFTER_SECONDS = 30;

    public TaxServiceUnavailableException(@NonNull String message) {
        super(message);
    }
}
