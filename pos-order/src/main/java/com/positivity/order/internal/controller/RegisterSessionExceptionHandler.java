package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.SessionCloseBlockedException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ApiError mapping for register-session endpoints (parity stories G1/G2). Scoped to
 * {@link RegisterSessionController} so it does not shadow the sales-order advice.
 */
@RestControllerAdvice(assignableTypes = RegisterSessionController.class)
@RequiredArgsConstructor
@Slf4j
public class RegisterSessionExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(RegisterSessionNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(RegisterSessionNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "REGISTER_SESSION_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(RegisterSessionConflictException.class)
    public ResponseEntity<ApiError> handleConflict(RegisterSessionConflictException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "REGISTER_SESSION_CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(SessionCloseBlockedException.class)
    public ResponseEntity<ApiError> handleCloseBlocked(SessionCloseBlockedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "SESSION_CLOSE_BLOCKED",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Invalid register-session argument: correlationId={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "REGISTER_SESSION_INVALID_ARGUMENT",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Register-session access denied: correlationId={}", correlationId, ex);
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
        return Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
    }
}
