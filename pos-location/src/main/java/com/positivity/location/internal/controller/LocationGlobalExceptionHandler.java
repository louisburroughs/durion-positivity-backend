package com.positivity.location.internal.controller;

import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidFieldException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Renders the {@link ResponseStatusException}s this module's services throw as the platform
 * {@link ApiError} envelope (ADR-0017 §3, #1720), with the correlation id in both the
 * {@code X-Correlation-Id} response header and the body (ADR-0017 §4, #1729): an inbound
 * {@code X-Correlation-Id} is echoed, otherwise a UUIDv7 is generated.
 *
 * <p>The services put the machine-readable error in the exception's reason
 * ({@code CYCLE_DETECTED}, {@code SITE_NOT_FOUND}, ...). A reason in that shape becomes
 * {@link ApiError#code()}, so a client branches on the same value it read from the RFC 9457
 * {@code detail} before this advice replaced the module's ProblemDetail rendering. Any other
 * reason is free text: the code falls back to the status and the text becomes the message.
 *
 * <p>Two module exceptions get their own rendering. An {@link InvalidFieldException} carries its
 * code and the field at fault, so it answers with {@code fieldErrors}. A
 * {@link DuplicateResourceException} is {@code @ResponseStatus(CONFLICT)} with no reason, which the
 * shared catch-all renders as a bare {@code CONFLICT}; its message is always a machine code
 * ({@code MOBILE_UNIT_NAME_TAKEN}, {@code BAY_NAME_TAKEN}, ...), so it becomes the code here and a
 * client can tell "name taken" from any other conflict (#2252).
 *
 * <p>Everything else — Spring MVC's own web exceptions, validation failures, the catch-all — is
 * left to pos-web-common's {@code GlobalApiExceptionHandler} (ADR-0056), which answers with the
 * same envelope. This advice is ordered just ahead of that catch-all so a
 * {@code ResponseStatusException} reaches it first; pos-security-common's highest-precedence
 * advice still answers {@code LOCATION_SCOPE_DENIED} before either.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class LocationGlobalExceptionHandler {

    static final String X_CORRELATION_ID = "X-Correlation-Id";

    private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final Clock clock;

    public LocationGlobalExceptionHandler(@NonNull Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(
            @NonNull ResponseStatusException ex, @NonNull HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpStatusCode status = ex.getStatusCode();
        String reason = ex.getReason();
        String code;
        String message;
        if (reason != null && MACHINE_CODE.matcher(reason).matches()) {
            code = reason;
            message = defaultMessage(status);
        } else {
            code = statusCode(status);
            message = reason == null || reason.isBlank() ? defaultMessage(status) : reason;
        }
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(InvalidFieldException.class)
    public ResponseEntity<ApiError> handleInvalidField(
            @NonNull InvalidFieldException ex, @NonNull HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpStatusCode status = ex.getStatusCode();
        String message = ex.getReason() == null ? defaultMessage(status) : ex.getReason();
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.withFieldErrors(
                        ex.getCode(),
                        message,
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        List.of(new ApiError.FieldError(ex.getField(), message))));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiError> handleDuplicateResource(
            @NonNull DuplicateResourceException ex, @NonNull HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpStatus status = HttpStatus.CONFLICT;
        String reason = ex.getMessage();
        String code = reason != null && MACHINE_CODE.matcher(reason).matches() ? reason : statusCode(status);
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        code,
                        defaultMessage(status),
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        String inbound = request.getHeader(X_CORRELATION_ID);
        return inbound == null || inbound.isBlank() ? UUIDv7Generator.generate().toString() : inbound.trim();
    }

    private static String statusCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "VALIDATION_ERROR";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 422 -> "UNPROCESSABLE_CONTENT";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
    }

    private static String defaultMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Request was rejected";
            case 404 -> "Requested resource was not found";
            case 409 -> "Request conflicts with the current state of the resource";
            case 422 -> "Request could not be processed";
            default -> status.is5xxServerError() ? "Unexpected error occurred" : "Request rejected";
        };
    }
}
