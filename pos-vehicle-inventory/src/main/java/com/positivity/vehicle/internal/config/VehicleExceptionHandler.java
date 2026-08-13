package com.positivity.vehicle.internal.config;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for pos-vehicle-inventory REST controllers.
 *
 * <p>
 * Without this advice the module returned bare statuses with empty bodies, and
 * constraint violations on path variables escaped as 500s. Every error leaving
 * this module now carries the {@link ApiError} envelope required by
 * {@code docs/ERROR_ENVELOPE.md} (ADR-0017), so a caller has a {@code code} to
 * branch on and a {@code correlationId} to quote.
 *
 * <p>
 * Handled exceptions:
 * <ul>
 * <li>{@link ConstraintViolationException} - 400 Bad Request (path variable and
 * request parameter constraints on {@code @Validated} controllers)</li>
 * <li>{@link MethodArgumentNotValidException} - 400 Bad Request (request body
 * validation)</li>
 * <li>{@link EntityNotFoundException} - 404 Not Found</li>
 * <li>{@link IllegalArgumentException} - 400 Bad Request (domain
 * validation)</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class VehicleExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    private final Clock clock;

    /**
     * The {@link Clock} bean comes from {@code pos-events} in a running service, but
     * a {@code @WebMvcTest} slice does not load that auto-configuration. Resolving
     * it lazily keeps this advice usable in a web slice without every slice having
     * to declare a clock of its own.
     */
    public VehicleExceptionHandler(ObjectProvider<Clock> clockProvider) {
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Constraints declared on path variables and request parameters of a
     * {@code @Validated} controller surface here. They describe a malformed request,
     * so they are a 400 — before this handler existed Spring reported them as a 500,
     * which made a client looping on a bad VIN look like a server outage.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Constraint violation on {}: {}", path(request), ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        List<ApiError.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(violation -> new ApiError.FieldError(fieldName(violation), violation.getMessage()))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.withFieldErrors(
                        VALIDATION_FAILED,
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Validation failed on {}: {}", path(request), ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.withFieldErrors(
                        VALIDATION_FAILED,
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleEntityNotFound(
            EntityNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Resource not found on {}: {}", path(request), ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        "RESOURCE_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Invalid argument on {}: {}", path(request), ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(
                        "VALIDATION_ERROR",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * A violation's property path is method-scoped — {@code getVehicleByVin.vin} —
     * so only the leaf node names something the caller sent.
     */
    private static String fieldName(ConstraintViolation<?> violation) {
        String leaf = null;
        for (Path.Node node : violation.getPropertyPath()) {
            leaf = node.getName();
        }
        return leaf != null ? leaf : violation.getPropertyPath().toString();
    }

    private static String path(HttpServletRequest request) {
        return request != null ? request.getRequestURI() : "";
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String correlationId = request.getHeader(X_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            return UUIDv7Generator.generate().toString();
        }
        return correlationId;
    }
}
