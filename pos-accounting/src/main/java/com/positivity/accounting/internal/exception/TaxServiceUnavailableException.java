package com.positivity.accounting.internal.exception;

import org.jspecify.annotations.NonNull;

/**
 * pos-tax could not answer (CAP:550 S32c's shape, added by #2615 for its reference reads): it is unreachable, answered
 * 5xx, or its answer was unreadable. 503 {@code SERVICE_UNAVAILABLE} with {@code Retry-After}; nothing is stored. The
 * message never carries a registration number, a secret or a request body.
 */
public class TaxServiceUnavailableException extends RuntimeException {

    public static final String CODE = "SERVICE_UNAVAILABLE";

    /** Seconds the caller should wait before retrying. */
    public static final int RETRY_AFTER_SECONDS = 30;

    public TaxServiceUnavailableException(@NonNull String message) {
        super(message);
    }
}
