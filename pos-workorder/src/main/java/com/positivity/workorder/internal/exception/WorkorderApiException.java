package com.positivity.workorder.internal.exception;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;

/**
 * A request failure whose status, ApiError code and message the controller already knows.
 * {@code GlobalExceptionHandler} renders it as the ApiError envelope with the correlation id,
 * replacing controller-built {@code Map{code, message}} bodies (ADR-0017 §3, #1720).
 */
public class WorkorderApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public WorkorderApiException(@NonNull HttpStatus status, @NonNull String code, @NonNull String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public @NonNull HttpStatus getStatus() {
        return status;
    }

    public @NonNull String getCode() {
        return code;
    }
}
