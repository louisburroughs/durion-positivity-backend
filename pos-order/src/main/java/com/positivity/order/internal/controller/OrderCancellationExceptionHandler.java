package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
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
    private final Clock clock;

    @ExceptionHandler(SalesOrderNotFoundException.class)
    public ResponseEntity<ApiError> handleSalesOrderNotFound(
            SalesOrderNotFoundException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("ORDER_NOT_FOUND", ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleInvalidCancellationState(
            IllegalStateException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("ORDER_CANCELLATION_INVALID", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Access denied: correlationId={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("ORDER_FORBIDDEN", ex.getMessage(),
                        HttpStatus.FORBIDDEN.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Invalid argument: correlationId={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of("ORDER_CANCELLATION_BAD_REQUEST", ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(), Instant.now(clock).toString(), correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}
