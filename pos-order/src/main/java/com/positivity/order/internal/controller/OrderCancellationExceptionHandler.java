package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OrderCancellationController.class)
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String TIMESTAMP = "timestamp";
    private static final String ERROR = "error";
    private static final String CODE = "code";
    private static final String STATUS = "status";
    private static final String CORRELATION_ID = "correlationId";
    private static final String MESSAGE = "message";

    private final Clock clock;

    @ExceptionHandler(SalesOrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleSalesOrderNotFound(
            SalesOrderNotFoundException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, clock.instant().toString());
        body.put(ERROR, "ORDER_NOT_FOUND");
        body.put(CODE, "ORDER_NOT_FOUND");
        body.put(STATUS, HttpStatus.NOT_FOUND.value());
        body.put(CORRELATION_ID, correlationId);
        body.put(MESSAGE, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCancellationState(
            IllegalStateException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, clock.instant().toString());
        body.put(ERROR, "ORDER_CANCELLATION_INVALID");
        body.put(CODE, "ORDER_CANCELLATION_INVALID");
        body.put(STATUS, HttpStatus.CONFLICT.value());
        body.put(CORRELATION_ID, correlationId);
        body.put(MESSAGE, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Access denied: correlationId={}", correlationId, ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, clock.instant().toString());
        body.put(ERROR, "ORDER_FORBIDDEN");
        body.put(CODE, "ORDER_FORBIDDEN");
        body.put(STATUS, HttpStatus.FORBIDDEN.value());
        body.put(CORRELATION_ID, correlationId);
        body.put(MESSAGE, ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_CORRELATION_ID, correlationId)
                .body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Invalid argument: correlationId={}", correlationId, ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, clock.instant().toString());
        body.put(ERROR, "ORDER_CANCELLATION_BAD_REQUEST");
        body.put(CODE, "ORDER_CANCELLATION_BAD_REQUEST");
        body.put(STATUS, HttpStatus.BAD_REQUEST.value());
        body.put(CORRELATION_ID, correlationId);
        body.put(MESSAGE, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(body);
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}