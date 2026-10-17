package com.positivity.order.internal.exception;

import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * A drawer-policy PUT that breaks a field rule (CAP:550 S16, #2512): 400 {@code VALIDATION_ERROR}, naming the
 * offending field by its JSON path so the Approval limits page can mark it (ADR-0017 {@code fieldErrors[]}, #2674).
 */
public class SessionPolicyValidationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String field;

    public SessionPolicyValidationException(@NonNull String field, @NonNull String message) {
        super(message);
        this.field = field;
    }

    /** The request's JSON path of the refused field, e.g. {@code pettyExpense.cashierLimit}. */
    public @NonNull String getField() {
        return field;
    }
}
