package com.positivity.order.internal.exception;

import com.positivity.shared.error.ApiError;
import java.util.List;

/**
 * A register-session request is malformed on its face: a non-positive cash-movement amount, or a
 * {@code movementType} that does not name a {@link com.positivity.order.internal.entity.CashMovementType}
 * (issue #1694). Distinct from {@link RegisterSessionConflictException}, which is a well-formed
 * request the session's current state refuses. Maps to a 400 {@code REGISTER_SESSION_INVALID_ARGUMENT}
 * ApiError — the code is unchanged from the blanket {@code IllegalArgumentException} handler this
 * type replaces; only the status moved, from 422 to 400 per ADR-0017 (request-shape validation, not
 * a domain-policy refusal of an otherwise-valid payload).
 */
public class RegisterSessionRequestValidationException extends RuntimeException {

    private final transient List<ApiError.FieldError> fieldErrors;

    public RegisterSessionRequestValidationException(String message) {
        this(message, List.of());
    }

    /**
     * A malformed request naming each offending field (ADR-0017 §3; CAP:550 S32d). The messages are value-free: the
     * handler logs this exception at WARN, and a supplier's number or name must never reach a log.
     *
     * @param message     a value-free summary
     * @param fieldErrors one entry per offending field
     */
    public RegisterSessionRequestValidationException(String message, List<ApiError.FieldError> fieldErrors) {
        super(message);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    /** The offending fields; empty for a request refused on its first malformed field. */
    public List<ApiError.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
