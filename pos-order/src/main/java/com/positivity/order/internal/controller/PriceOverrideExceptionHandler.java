package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.InvalidPriceOverrideException;
import com.positivity.order.internal.exception.PriceOverrideIdempotencyConflictException;
import com.positivity.order.internal.exception.PriceOverrideNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    private final Clock clock;

    @ExceptionHandler(PriceOverrideNotFoundException.class)
    public ResponseEntity<ApiError> handleOverrideNotFound(
            PriceOverrideNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_PRICE_OVERRIDE_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(InvalidPriceOverrideException.class)
    public ResponseEntity<ApiError> handleInvalidOverride(
            InvalidPriceOverrideException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_PRICE_OVERRIDE_INVALID",
                        ex.getMessage(),
                        422,
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(PriceOverrideIdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            PriceOverrideIdempotencyConflictException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_PRICE_OVERRIDE_IDEMPOTENCY_CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        String correlationId = Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
        log.warn("Invalid argument: correlationId={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_PRICE_OVERRIDE_BAD_REQUEST",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String correlationId = Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
        log.warn("Validation failed: correlationId={}", correlationId, ex);
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"))
                .toList();
        return ResponseEntity.badRequest()
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.withFieldErrors(
                        "VALIDATION_FAILED",
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String correlationId = Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
        log.warn("Access denied: correlationId={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_FORBIDDEN",
                        ex.getMessage(),
                        HttpStatus.FORBIDDEN.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}
