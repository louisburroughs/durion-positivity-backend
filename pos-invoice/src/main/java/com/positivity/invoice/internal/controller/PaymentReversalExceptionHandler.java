package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.InsufficientRefundableAmountException;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.PaymentGatewayException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.exception.PaymentWindowExpiredException;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice(assignableTypes = PaymentReversalController.class)
@RequiredArgsConstructor
public class PaymentReversalExceptionHandler {

    private final Clock clock;

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String MESSAGE = "message";
    private static final String ERROR = "error";
    private static final String TIMESTAMP = "timestamp";
    private static final String CODE = "code";
    private static final String STATUS = "status";
    private static final String CORRELATION_ID = "correlationId";

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

    @ExceptionHandler(PaymentWindowExpiredException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentWindowExpired(
            PaymentWindowExpiredException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "PAYMENT_WINDOW_EXPIRED",
                        CODE, "PAYMENT_WINDOW_EXPIRED",
                        STATUS, 422,
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(InsufficientRefundableAmountException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientRefundableAmount(
            InsufficientRefundableAmountException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "INSUFFICIENT_REFUNDABLE_AMOUNT",
                        CODE, "INSUFFICIENT_REFUNDABLE_AMOUNT",
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

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentGatewayException(
            PaymentGatewayException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "INTERNAL_SERVER_ERROR",
                        CODE, "INTERNAL_SERVER_ERROR",
                        STATUS, HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}