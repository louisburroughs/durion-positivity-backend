package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.ReprintLimitExceededException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.ReceiptNotFoundException;
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

@RestControllerAdvice(assignableTypes = ReceiptController.class)
@RequiredArgsConstructor
public class ReceiptExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(ReprintLimitExceededException.class)
    public ResponseEntity<ApiError> handleReprintLimitExceeded(
            ReprintLimitExceededException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("REPRINT_LIMIT_EXCEEDED", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(ReceiptNotFoundException.class)
    public ResponseEntity<ApiError> handleReceiptNotFoundException(
            ReceiptNotFoundException ex, HttpServletRequest request) {
        return handleNotFound(ex.getMessage(), request);
    }

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ResponseEntity<ApiError> handleInvoiceNotFoundException(
            InvoiceNotFoundException ex, HttpServletRequest request) {
        return handleNotFound(ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> handleNotFound(String message, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("NOT_FOUND", message,
                        HttpStatus.NOT_FOUND.value(), Instant.now(clock).toString(), correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}
