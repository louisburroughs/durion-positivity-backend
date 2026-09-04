package com.positivity.warranty.internal.exception;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Module-wide translation of exceptions into the standard {@code ApiError} envelope
 * ({@code docs/ERROR_ENVELOPE.md}) for every pos-warranty controller.
 *
 * <p>Deliberately does NOT map bare {@code IllegalArgumentException} or {@code Exception}: see
 * {@link WarrantyValidationException} for why a blanket {@code IllegalArgumentException} handler
 * misreports server defects as client errors, and ADR-0056 for why the unmapped tail must reach
 * {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler} (registered at {@code
 * Ordered.LOWEST_PRECEDENCE}, so this advice keeps precedence for every type it does map) rather
 * than being swallowed by a module-local catch-all here.
 */
@RestControllerAdvice(basePackages = "com.positivity.warranty.internal.controller")
public class WarrantyExceptionHandler {
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";

    private static final Logger log = LoggerFactory.getLogger(WarrantyExceptionHandler.class);
    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private final Clock clock;

    public WarrantyExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(WarrantyNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(WarrantyNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), request);
    }

    /** State-machine violations return 409 with {@code nextAction} listing the legal moves (PRD §5). */
    @ExceptionHandler(IllegalClaimStateException.class)
    public ResponseEntity<ApiError> handleIllegalClaimState(IllegalClaimStateException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.guided(
                        ex.getCode(),
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        null,
                        ex.getNextAction(),
                        null),
                headers,
                HttpStatus.CONFLICT);
    }

    /** Well-formed request referencing unresolvable cross-service data (e.g. unknown workorder). */
    @ExceptionHandler(WarrantyUnprocessableException.class)
    public ResponseEntity<ApiError> handleUnprocessable(WarrantyUnprocessableException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), request);
    }

    /**
     * Outbound write to a sibling service failed (settlement calls to pos-invoice fail loudly,
     * PRD §9.4) — surfaced as 502 instead of the generic 500 so callers can distinguish an
     * integration outage from a warranty bug.
     */
    @ExceptionHandler(WarrantyIntegrationException.class)
    public ResponseEntity<ApiError> handleIntegrationFailure(
            WarrantyIntegrationException ex, HttpServletRequest request) {
        log.error("Outbound integration call failed", ex);
        return build(HttpStatus.BAD_GATEWAY, "WARRANTY_INTEGRATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() == null ? "invalid value" : fe.getDefaultMessage()))
                .toList();
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.withFieldErrors(
                        VALIDATION_ERROR,
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors),
                headers,
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "MISSING_PARAMETER",
                "Required request parameter '" + ex.getParameterName() + "' is missing",
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER",
                "Parameter '" + ex.getName() + "' has an invalid value",
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body could not be parsed", request);
    }

    /**
     * Genuine client input-validation failures raised by this module's own services/controllers
     * (see {@link WarrantyValidationException}). This class deliberately does NOT map bare {@code
     * IllegalArgumentException}: that type is not exclusive to this module's validation —
     * Hibernate/JPA throw it for an invalid query and {@code UUID.fromString} throws it on
     * malformed stored data, and catching it here would turn a server-side defect into a
     * client-facing 400 that also leaks internal class names and query text. An unexpected {@code
     * IllegalArgumentException} now falls through to {@code pos-web-common}'s platform-wide
     * {@code GlobalApiExceptionHandler} fallback, which answers a generic, correlated 500 instead
     * of echoing the exception text.
     */
    @ExceptionHandler(WarrantyValidationException.class)
    public ResponseEntity<ApiError> handleValidation(WarrantyValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage(), request);
    }

    /** {@code @PreAuthorize} denials must not fall into the platform 500 fallback. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action", request);
    }

    /**
     * Concurrent writes to the same versioned aggregate (every mutating claim flow is
     * load-modify-save on an {@code @Version}-ed entity) are expected, retryable conflicts —
     * 409, never the platform 500 fallback (mirrors pos-catalog {@code CatalogExceptionHandler}).
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiError> handleOptimisticLockConflict(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", "Resource was updated concurrently. Please retry.", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId),
                headers,
                status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}
