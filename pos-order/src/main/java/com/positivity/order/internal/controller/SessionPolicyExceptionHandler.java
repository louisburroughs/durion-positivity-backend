package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.CurrencyNotSupportedException;
import com.positivity.order.internal.exception.SessionPolicyConflictException;
import com.positivity.order.internal.exception.SessionPolicyValidationException;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors of the drawer-policy endpoints (CAP:550 S16, #2512). */
@RestControllerAdvice(assignableTypes = SessionPolicyController.class)
@RequiredArgsConstructor
@Slf4j
public class SessionPolicyExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(SessionPolicyValidationException.class)
    public ResponseEntity<ApiError> handleValidation(SessionPolicyValidationException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "VALIDATION_ERROR",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /** ADR-0067 R-1: a policy stated in a currency other than the functional currency. */
    @ExceptionHandler(CurrencyNotSupportedException.class)
    public ResponseEntity<ApiError> handleCurrency(CurrencyNotSupportedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "CURRENCY_NOT_SUPPORTED",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(SessionPolicyConflictException.class)
    public ResponseEntity<ApiError> handleConflict(SessionPolicyConflictException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.info("Drawer policy change lost a race: correlationId={}", correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "SESSION_POLICY_CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
    }
}
