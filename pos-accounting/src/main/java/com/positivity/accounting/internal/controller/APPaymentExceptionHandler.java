package com.positivity.accounting.internal.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.positivity.accounting.internal.dto.ErrorResponse;
import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;

/**
 * Global exception handler for AP Payment operations.
 * 
 * Maps domain exceptions to appropriate HTTP status codes using standard
 * ErrorResponse format:
 * - EventNotFoundException → 404 Not Found
 * - IdempotencyConflictException → 409 Conflict
 * - PaymentGatewayException → 500 Internal Server Error
 * - IllegalArgumentException → 400 Bad Request (validation errors)
 */
@RestControllerAdvice(basePackages = "com.positivity.accounting.internal.controller")
public class APPaymentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(APPaymentExceptionHandler.class);

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEventNotFound(EventNotFoundException ex) {
        log.warn("Event not found: {}", ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .errorCode("EVENT_NOT_FOUND")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .errorCode("IDEMPOTENCY_CONFLICT")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ErrorResponse> handlePaymentGatewayException(PaymentGatewayException ex) {
        if (ex.getPaymentRef() != null) {
            log.error("Payment gateway error for payment {}: {}", ex.getPaymentRef(), ex.getMessage(), ex);
        } else {
            log.error("Payment gateway error: {}", ex.getMessage(), ex);
        }
        ErrorResponse body = ErrorResponse.builder()
                .errorCode("PAYMENT_GATEWAY_FAILURE")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .errorCode("VALIDATION_ERROR")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
