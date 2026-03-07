package com.positivity.invoice.internal.controller;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.PaymentDeclinedException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.shared.id.UUIDv7Generator;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestControllerAdvice(assignableTypes = PaymentController.class)
@RequiredArgsConstructor
public class PaymentExceptionHandler {
    private final Clock clock;

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String MESSAGE = "message";
    private static final String ERROR = "error";
    private static final String TIMESTAMP = "timestamp";
    private static final String CODE = "code";
    private static final String STATUS = "status";
    private static final String CORRELATION_ID = "correlationId";

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidPaymentState(
            InvalidPaymentStateException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "INVALID_PAYMENT_STATE",
                        CODE, "INVALID_PAYMENT_STATE",
                        STATUS, HttpStatus.CONFLICT.value(),
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(PaymentIntentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            PaymentIntentNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "NOT_FOUND",
                        CODE, "NOT_FOUND",
                        STATUS, HttpStatus.NOT_FOUND.value(),
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentDeclined(
            PaymentDeclinedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "PAYMENT_DECLINED",
                        CODE, "PAYMENT_DECLINED",
                        STATUS, 422,
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "BAD_REQUEST",
                        CODE, "BAD_REQUEST",
                        STATUS, HttpStatus.BAD_REQUEST.value(),
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(
            AccessDeniedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "FORBIDDEN",
                        CODE, "FORBIDDEN",
                        STATUS, HttpStatus.FORBIDDEN.value(),
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}