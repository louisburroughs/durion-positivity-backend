package com.positivity.customer.internal.exception;

/**
 * The caller has exceeded a rate limit. Maps to {@code 429}.
 *
 * <p>Raised by the unauthenticated public inquiry form, where the submitter is not a trusted
 * caller and the only defence against a flood is a ceiling.
 */
public class CrmTooManyRequestsException extends RuntimeException {

    public CrmTooManyRequestsException(String message) {
        super(message);
    }
}
