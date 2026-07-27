package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.InvalidOrderStateTransitionException;
import com.positivity.order.internal.exception.OrderNotEditableException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Order-wide handler for state-machine and concurrency violations (plan story A1). All three map
 * to {@code 409 Conflict} per ADR-0017: the request was well-formed but conflicts with the order's
 * current state or a concurrent modification.
 */
@RestControllerAdvice
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class OrderStateExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(OrderNotEditableException.class)
    public ResponseEntity<ApiError> handleNotEditable(OrderNotEditableException ex, HttpServletRequest request) {
        return conflict("ORDER_NOT_EDITABLE", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidOrderStateTransitionException.class)
    public ResponseEntity<ApiError> handleInvalidTransition(
            InvalidOrderStateTransitionException ex, HttpServletRequest request) {
        return conflict("ORDER_INVALID_STATE_TRANSITION", ex.getMessage(), request);
    }

    @ExceptionHandler(com.positivity.order.internal.exception.CartIdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            com.positivity.order.internal.exception.CartIdempotencyConflictException ex, HttpServletRequest request) {
        return conflict("ORDER_IDEMPOTENCY_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Concurrent modification detected: {}", ex.getMessage());
        return conflict("ORDER_CONFLICT", "The order was modified concurrently; retry with fresh state", request);
    }

    private ResponseEntity<ApiError> conflict(String code, String message, HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        String correlationId = (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        code,
                        message,
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }
}
