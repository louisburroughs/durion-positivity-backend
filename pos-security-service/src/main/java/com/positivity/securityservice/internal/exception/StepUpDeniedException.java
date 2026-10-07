package com.positivity.securityservice.internal.exception;

/**
 * A step-up credential check failed (CAP:550 S16, #2512; AW31): wrong credentials, or an unknown,
 * disabled, expired or locked account. The reason stays in the server log; every refusal answers the
 * same 403 body, so the check is not an account-state oracle, and never 401, which a caller would
 * read as its own session failing.
 */
public class StepUpDeniedException extends RuntimeException {

    private final String reason;

    public StepUpDeniedException(String reason) {
        super("Step-up denied");
        this.reason = reason;
    }

    /** The reason for the log, never for the response. */
    public String reason() {
        return reason;
    }
}
