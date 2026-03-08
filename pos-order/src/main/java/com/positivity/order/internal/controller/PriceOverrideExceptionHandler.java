package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.InvalidPriceOverrideException;
import com.positivity.order.internal.exception.PriceOverrideNotFoundException;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PriceOverrideController.class)
@RequiredArgsConstructor
@Slf4j
public class PriceOverrideExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String TIMESTAMP = "timestamp";
    private static final String ERROR = "error";
    private static final String CODE = "code";
    private static final String STATUS = "status";
    private static final String CORRELATION_ID = "correlationId";
    private static final String MESSAGE = "message";
    private static final String ORDER_PRICE_OVERRIDE_BAD_REQUEST = "ORDER_PRICE_OVERRIDE_BAD_REQUEST";

    private final Clock clock;

    @ExceptionHandler(PriceOverrideNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOverrideNotFound(
            PriceOverrideNotFoundException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "ORDER_PRICE_OVERRIDE_NOT_FOUND",
                        CODE, "ORDER_PRICE_OVERRIDE_NOT_FOUND",
                        STATUS, HttpStatus.NOT_FOUND.value(),
                        CORRELATION_ID, correlationId,
                        MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(InvalidPriceOverrideException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOverride(
            InvalidPriceOverrideException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        ERROR, "ORDER_PRICE_OVERRIDE_INVALID",
                        CODE, "ORDER_PRICE_OVERRIDE_INVALID",
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
        body.put(ERROR, ORDER_PRICE_OVERRIDE_BAD_REQUEST);
        body.put(CODE, ORDER_PRICE_OVERRIDE_BAD_REQUEST);
        body.put(STATUS, HttpStatus.BAD_REQUEST.value());
        body.put(CORRELATION_ID, correlationId);
        body.put(MESSAGE, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String correlationId = Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
        log.warn("Validation failed: correlationId={}", correlationId, ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, clock.instant().toString());
        body.put(ERROR, "VALIDATION_FAILED");
        body.put(CODE, ORDER_PRICE_OVERRIDE_BAD_REQUEST);
        body.put(STATUS, HttpStatus.BAD_REQUEST.value());
        body.put(CORRELATION_ID, correlationId);
        body.put(MESSAGE, "Request validation failed");
        body.put("fieldErrors", ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), MESSAGE,
                        fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"))
                .toList());
        return ResponseEntity.badRequest()
                .header(X_CORRELATION_ID, correlationId)
                .body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex,
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