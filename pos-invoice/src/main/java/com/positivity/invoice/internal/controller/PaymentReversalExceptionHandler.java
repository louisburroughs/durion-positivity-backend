package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.InsufficientRefundableAmountException;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.PaymentGatewayException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.exception.PaymentWindowExpiredException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;

@RestControllerAdvice(assignableTypes = PaymentReversalController.class)
@RequiredArgsConstructor
public class PaymentReversalExceptionHandler {

    private final Clock clock;
    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler(PaymentIntentNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            PaymentIntentNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("NOT_FOUND", ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ApiError> handleInvalidPaymentState(
            InvalidPaymentStateException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("INVALID_PAYMENT_STATE", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(PaymentWindowExpiredException.class)
    public ResponseEntity<ApiError> handlePaymentWindowExpired(
            PaymentWindowExpiredException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("PAYMENT_WINDOW_EXPIRED", ex.getMessage(),
                        422, Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(InsufficientRefundableAmountException.class)
    public ResponseEntity<ApiError> handleInsufficientRefundableAmount(
            InsufficientRefundableAmountException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("INSUFFICIENT_REFUNDABLE_AMOUNT", ex.getMessage(),
                        422, Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(
            IllegalArgumentException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("BAD_REQUEST", ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ApiError> handlePaymentGatewayException(
            PaymentGatewayException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("INTERNAL_SERVER_ERROR", ex.getMessage(),
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), Instant.now(clock).toString(), correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}
