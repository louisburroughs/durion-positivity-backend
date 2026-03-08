package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.InvalidSkuException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SalesOrderController.class)
@RequiredArgsConstructor
@Slf4j
public class SalesOrderExceptionHandler {

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
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "ORDER_NOT_FOUND",
                        CODE, "ORDER_NOT_FOUND",
                        STATUS, HttpStatus.NOT_FOUND.value(),
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(InvalidSkuException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSku(
            InvalidSkuException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "ORDER_INVALID_SKU",
                        CODE, "ORDER_INVALID_SKU",
                        STATUS, HttpStatus.BAD_REQUEST.value(),
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleUnprocessableRequest(
            IllegalStateException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "UNPROCESSABLE_REQUEST",
                        CODE, "UNPROCESSABLE_REQUEST",
                        STATUS, 422,
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        String correlationId = Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
        log.warn("Invalid argument: correlationId={}", correlationId, ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, clock.instant().toString());
        body.put(ERROR, "INVALID_ARGUMENT");
        body.put(CODE, "ORDER_INVALID_ARGUMENT");
        body.put(STATUS, HttpStatus.UNPROCESSABLE_ENTITY.value());
        body.put(CORRELATION_ID, correlationId);
        body.put(MESSAGE, ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header(X_CORRELATION_ID, correlationId)
                .body(body);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex,
            HttpServletRequest request) {
        String correlationId = Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
        log.warn("Access denied: correlationId={}", correlationId, ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, clock.instant().toString());
        body.put(ERROR, "FORBIDDEN");
        body.put(CODE, "ORDER_FORBIDDEN");
        body.put(STATUS, HttpStatus.FORBIDDEN.value());
        body.put(CORRELATION_ID, correlationId);
        body.put(MESSAGE, ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_CORRELATION_ID, correlationId)
                .body(body);
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}