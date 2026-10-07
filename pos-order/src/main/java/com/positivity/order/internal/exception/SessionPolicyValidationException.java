package com.positivity.order.internal.exception;

/** A drawer-policy PUT that breaks a field rule (CAP:550 S16, #2512): 400 {@code VALIDATION_ERROR}. */
public class SessionPolicyValidationException extends RuntimeException {

    public SessionPolicyValidationException(String message) {
        super(message);
    }
}
