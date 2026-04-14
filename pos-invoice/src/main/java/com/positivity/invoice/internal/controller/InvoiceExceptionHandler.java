package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = InvoiceController.class)
@RequiredArgsConstructor
public class InvoiceExceptionHandler {
    private final Clock clock;

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ResponseEntity<ApiError> handleInvoiceNotFound(InvoiceNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(InvalidInvoiceStateException.class)
    public ResponseEntity<ApiError> handleInvalidInvoiceState(
            InvalidInvoiceStateException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "INVALID_STATE",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * C3 (ADR-0017): {@link IllegalStateException} maps to HTTP 409 Conflict.
     * Prevents "already finalized" and "POSTED" state errors from returning 500.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        List<ApiError.FieldError> fieldErrors = msg.toLowerCase().contains("approval code")
                ? List.of(new ApiError.FieldError("managerApprovalCode", msg))
                : null;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(
                        fieldErrors != null
                                ? ApiError.withFieldErrors(
                                        "VALIDATION_ERROR",
                                        msg,
                                        HttpStatus.BAD_REQUEST.value(),
                                        Instant.now(clock).toString(),
                                        correlationId,
                                        fieldErrors)
                                : ApiError.of(
                                        "VALIDATION_ERROR",
                                        msg,
                                        HttpStatus.BAD_REQUEST.value(),
                                        Instant.now(clock).toString(),
                                        correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}
