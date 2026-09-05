package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.OrderCancellationStateConflictException;
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
            SalesOrderNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * A well-formed cancellation request the order's current state refuses. ADR-0017 §2 makes
     * that a 409 — the code and status this case already answered.
     *
     * <p>#1730: this replaces a blanket {@code @ExceptionHandler(IllegalStateException.class)}
     * that also answered 409 for two downstream call failures ("workorder cancellation failed",
     * "payment reversal failed"). Those are server-side problems, and a 409 told the caller its
     * request conflicted with state — so it would not retry, and nothing surfaced the failure as
     * a 5xx. They stay untyped and now reach pos-web-common's platform advice as a correlated
     * 500.
     */
    @ExceptionHandler(OrderCancellationStateConflictException.class)
    public ResponseEntity<ApiError> handleInvalidCancellationState(
            OrderCancellationStateConflictException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_CANCELLATION_INVALID",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
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

    // No @ExceptionHandler(IllegalArgumentException.class) here (issue #1694): nothing reachable
    // through OrderCancellationController ever throws one — OrderCancellationServiceImpl only
    // raises SalesOrderNotFoundException and IllegalStateException, both mapped above. The blanket
    // handler this replaced would have silently turned a genuine server-side defect (a raw
    // IllegalArgumentException from Hibernate/JPA or a UUID parse) into a client 400 that echoed
    // internal detail; removing it lets such a case fall through to pos-web-common's
    // GlobalApiExceptionHandler, which answers a correlated 500 without leaking the message.

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}
