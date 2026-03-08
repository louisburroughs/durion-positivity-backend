package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.ReprintLimitExceededException;
import com.positivity.invoice.internal.exception.ReceiptNotFoundException;
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

@RestControllerAdvice(assignableTypes = ReceiptController.class)
@RequiredArgsConstructor
public class ReceiptExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String MESSAGE = "message";
    private static final String ERROR = "error";
    private static final String TIMESTAMP = "timestamp";
    private static final String CODE = "code";
    private static final String STATUS = "status";
    private static final String CORRELATION_ID = "correlationId";

    private final Clock clock;

    @ExceptionHandler(ReprintLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleReprintLimitExceeded(
            ReprintLimitExceededException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "REPRINT_LIMIT_EXCEEDED",
                        CODE, "REPRINT_LIMIT_EXCEEDED",
                        STATUS, HttpStatus.CONFLICT.value(),
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(ReceiptNotFoundException.class)
    public ResponseEntity<Object> handleReceiptNotFoundException(
            ReceiptNotFoundException ex, HttpServletRequest request) {
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

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}
