package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.PaymentDeclinedException;
import com.positivity.invoice.internal.exception.PaymentIdempotencyConflictException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PaymentController.class)
@RequiredArgsConstructor
public class PaymentExceptionHandler {
    private final Clock clock;

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ApiError> handleInvalidPaymentState(
            InvalidPaymentStateException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "INVALID_PAYMENT_STATE",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(PaymentIdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            PaymentIdempotencyConflictException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "PAYMENT_IDEMPOTENCY_CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(PaymentIntentNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(PaymentIntentNotFoundException ex, HttpServletRequest request) {
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

    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<ApiError> handlePaymentDeclined(PaymentDeclinedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "PAYMENT_DECLINED",
                        ex.getMessage(),
                        422,
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "BAD_REQUEST",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleForbidden(AccessDeniedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "FORBIDDEN",
                        ex.getMessage(),
                        HttpStatus.FORBIDDEN.value(),
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
