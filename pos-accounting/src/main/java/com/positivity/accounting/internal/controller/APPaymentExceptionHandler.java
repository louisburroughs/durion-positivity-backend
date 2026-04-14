package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.shared.error.ApiError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for AP Payment operations.
 *
 * Maps domain exceptions to appropriate HTTP status codes using standard
 * ApiError format:
 * - EventNotFoundException → 404 Not Found
 * - IdempotencyConflictException → 409 Conflict
 * - PaymentGatewayException → 500 Internal Server Error
 * - IllegalArgumentException → 400 Bad Request (validation errors)
 */
@RestControllerAdvice(basePackages = "com.positivity.accounting.internal.controller")
@RequiredArgsConstructor
public class APPaymentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(APPaymentExceptionHandler.class);
    private final Clock clock;

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ApiError> handleEventNotFound(EventNotFoundException ex) {
        log.warn("Event not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        "EVENT_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        null));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException ex) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        "IDEMPOTENCY_CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        null));
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ApiError> handlePaymentGatewayException(PaymentGatewayException ex) {
        if (ex.getPaymentRef() != null) {
            log.error("Payment gateway error for payment {}: {}", ex.getPaymentRef(), ex.getMessage(), ex);
        } else {
            log.error("Payment gateway error: {}", ex.getMessage(), ex);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(
                        "PAYMENT_GATEWAY_FAILURE",
                        ex.getMessage(),
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        Instant.now(clock).toString(),
                        null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(
                        "VALIDATION_ERROR",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        null));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse("Validation failed");

        log.warn("Validation error: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(
                        "VALIDATION_ERROR",
                        message,
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        null));
    }
}
