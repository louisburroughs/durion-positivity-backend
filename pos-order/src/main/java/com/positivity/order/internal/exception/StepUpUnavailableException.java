package com.positivity.order.internal.exception;

/**
 * pos-security-service could not be asked to verify a manager's credentials (CAP:550 S16, #2512): the
 * call failed for a reason other than a refusal. Answered 503 so the register does not read it as a
 * wrong password.
 */
public class StepUpUnavailableException extends RuntimeException {

    public StepUpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
