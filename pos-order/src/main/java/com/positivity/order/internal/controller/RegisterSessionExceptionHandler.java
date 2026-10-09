package com.positivity.order.internal.controller;

import com.positivity.order.internal.config.TaxCheckSettings;
import com.positivity.order.internal.exception.CashMovementIdempotencyConflictException;
import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.CashMovementTaxRefusedException;
import com.positivity.order.internal.exception.CurrencyNotSupportedException;
import com.positivity.order.internal.exception.RegisterFloatLocationMismatchException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.exception.SessionCloseBlockedException;
import com.positivity.order.internal.exception.StepUpUnavailableException;
import com.positivity.order.internal.exception.TaxCheckUnavailableException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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
@Slf4j
public class RegisterSessionExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 5;
    private final Clock clock;
    private final long taxCheckRetryAfterSeconds;

    /** A handler whose 503 {@code TAX_CHECK_UNAVAILABLE} carries the default {@code Retry-After} of 5 seconds. */
    public RegisterSessionExceptionHandler(Clock clock) {
        this(clock, DEFAULT_RETRY_AFTER_SECONDS);
    }

    /**
     * @param clock                     the clock of the error timestamps
     * @param taxCheckRetryAfterSeconds the {@code Retry-After} of 503 {@code TAX_CHECK_UNAVAILABLE}; {@link
     *                                  TaxCheckSettings} fails startup when it is shorter than the client's timeouts
     */
    @Autowired
    public RegisterSessionExceptionHandler(
            Clock clock,
            @Value("${" + TaxCheckSettings.RETRY_AFTER_PROPERTY + ":" + DEFAULT_RETRY_AFTER_SECONDS + "}")
                    long taxCheckRetryAfterSeconds) {
        this.clock = clock;
        this.taxCheckRetryAfterSeconds = taxCheckRetryAfterSeconds;
    }

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

    /**
     * A malformed cash-movement request (non-positive amount, unknown movementType). Maps to 400
     * per ADR-0017 — request-shape validation, not a domain-policy refusal — replacing the former
     * blanket {@code IllegalArgumentException} handler that answered 422 for this same case (a
     * status the issue #1694 audit found was never deliberate). The {@code REGISTER_SESSION_INVALID_ARGUMENT}
     * code is unchanged so the wire contract's error code does not drift; only the status moved.
     */
    @ExceptionHandler(RegisterSessionRequestValidationException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(
            RegisterSessionRequestValidationException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        // The message is value-free (CAP:550 S32d item 7): a supplier's number or name never reaches this log.
        log.warn("Invalid register-session request: correlationId={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.withFieldErrors(
                        "REGISTER_SESSION_INVALID_ARGUMENT",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        ex.fieldErrors().isEmpty() ? null : ex.fieldErrors()));
    }

    /**
     * A drawer rule refused a cash movement or its approval (CAP:550 S16, #2512): 403 for the approval
     * rules and 422 for the policy, category and float rules, each with its own code. The message never
     * says why a step-up check failed.
     */
    @ExceptionHandler(CashMovementRefusedException.class)
    public ResponseEntity<ApiError> handleCashMovementRefused(
            CashMovementRefusedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        HttpStatus status = HttpStatus.valueOf(ex.refusal().status());
        log.info("Cash movement refused code={} correlationId={}", ex.refusal().code(), correlationId);
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        ex.refusal().code(),
                        ex.getMessage(),
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * A petty expense's amount or stated tax refused by a rule its shape cannot show (CAP:550 S32d items 5 and 6):
     * 422 with the refusal's code and {@code fieldErrors} naming each offending field. A pos-tax {@code
     * TAX_AMOUNT_IMPLAUSIBLE} arrives here with pos-tax's message and field errors, under this request's correlation
     * id. Nothing is recorded.
     */
    @ExceptionHandler(CashMovementTaxRefusedException.class)
    public ResponseEntity<ApiError> handleCashMovementTaxRefused(
            CashMovementTaxRefusedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.info("Cash movement refused code={} correlationId={}", ex.code(), correlationId);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.withFieldErrors(
                        ex.code().name(),
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        ex.fieldErrors()));
    }

    /**
     * pos-tax could not check a petty expense that carries the supplier's number (CAP:550 S32d, amendment A4): 503
     * {@code TAX_CHECK_UNAVAILABLE} with {@code Retry-After} (ADR-0017 §1), never shorter than the wait already spent
     * ({@link TaxCheckSettings}).
     */
    @ExceptionHandler(TaxCheckUnavailableException.class)
    public ResponseEntity<ApiError> handleTaxCheckUnavailable(
            TaxCheckUnavailableException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Cash movement tax check unavailable: correlationId={}", correlationId);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(X_CORRELATION_ID, correlationId)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(taxCheckRetryAfterSeconds))
                .body(ApiError.of(
                        "TAX_CHECK_UNAVAILABLE",
                        ex.getMessage(),
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * #2573: a drawer opened away from its register's float location. The details carry the terminal, the
     * requested location and, only when the caller's scope covers it, the float's location.
     */
    @ExceptionHandler(RegisterFloatLocationMismatchException.class)
    public ResponseEntity<ApiError> handleRegisterFloatLocationMismatch(
            RegisterFloatLocationMismatchException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        List<ApiError.FieldError> details = new ArrayList<>();
        details.add(new ApiError.FieldError("terminalId", ex.terminalId()));
        if (ex.requestedLocationId() != null) {
            details.add(new ApiError.FieldError(
                    "requestedLocationId", ex.requestedLocationId().toString()));
        }
        if (ex.floatLocationId() != null) {
            details.add(new ApiError.FieldError(
                    "floatLocationId", ex.floatLocationId().toString()));
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.withFieldErrors(
                        "REGISTER_FLOAT_LOCATION_MISMATCH",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        details));
    }

    /** ADR-0067 R-1: a drawer amount in a currency other than the functional currency (CAP:550 S16). */
    @ExceptionHandler(CurrencyNotSupportedException.class)
    public ResponseEntity<ApiError> handleCurrencyNotSupported(
            CurrencyNotSupportedException ex, HttpServletRequest request) {
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

    /** A cash-movement requestId reused for another payload (CAP:550 S16, #2512; §8.2). */
    @ExceptionHandler(CashMovementIdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            CashMovementIdempotencyConflictException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "IDEMPOTENCY_CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /** The step-up check could not be made (CAP:550 S16, #2512): 503, not a refusal. */
    @ExceptionHandler(StepUpUnavailableException.class)
    public ResponseEntity<ApiError> handleStepUpUnavailable(StepUpUnavailableException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Cash movement approval check unavailable: correlationId={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "CASH_MOVEMENT_APPROVAL_UNAVAILABLE",
                        ex.getMessage(),
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
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
