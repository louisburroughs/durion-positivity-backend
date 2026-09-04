package com.positivity.vehicle.internal.config;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.vehicle.internal.exception.VehicleValidationException;
import com.positivity.vehicle.internal.exception.VehicleVinConflictException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
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
 * constraint violations on path variables escaped as 500s. The exceptions listed
 * below now carry the {@link ApiError} envelope required by
 * {@code docs/ERROR_ENVELOPE.md} (ADR-0017), so a caller has a {@code code} to
 * branch on and a {@code correlationId} to quote:
 * <ul>
 * <li>{@link ConstraintViolationException} - 400 Bad Request (path variable and
 * request parameter constraints on {@code @Validated} controllers)</li>
 * <li>{@link MethodArgumentNotValidException} - 400 Bad Request (request body
 * validation)</li>
 * <li>{@link EntityNotFoundException} - 404 Not Found</li>
 * <li>{@link VehicleValidationException} - 400 Bad Request (malformed request /
 * field-level validation, ADR-0017 §1)</li>
 * <li>{@link VehicleVinConflictException} - 409 Conflict (duplicate active VIN,
 * a stateful collision per ADR-0017 §2)</li>
 * </ul>
 *
 * <p>
 * Until issue #1694 this advice also mapped a blanket {@link IllegalArgumentException}
 * to 400, which caught not only this module's own domain validation but any
 * {@code IllegalArgumentException} reachable from a controller for any reason —
 * Hibernate/JPA on an invalid query, {@code UUID.fromString} on malformed stored
 * data, a JPA attribute converter on corrupt stored JSON — echoing internal
 * exception text and class names into a 4xx response as if it were a client
 * error. That handler is gone; the module's own throw sites were retyped to
 * {@link VehicleValidationException}/{@link VehicleVinConflictException} above, and
 * a handful of internal-invariant checks stay bare {@link IllegalArgumentException}
 * (commented at each site) that this advice no longer maps.
 *
 * <p>
 * There is also no catch-all {@code @ExceptionHandler(Exception.class)} here: an
 * unmapped runtime exception — including a bare {@code IllegalArgumentException}
 * from one of those internal-invariant sites — now falls through to
 * {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}
 * (pulled in transitively via {@code pos-security-common}), which answers a
 * generic, correlated 500 {@code INTERNAL_ERROR} that never echoes the
 * exception's own message, and separately restores ADR-0056 §2's
 * {@code DataIntegrityViolationException} mapping (409/422 by SQLSTATE) for this
 * module. A malformed JSON body is a bare 400, and the 401/403 raised by the
 * security filter chain never reaches an advice at all. Widening the coverage
 * means adding handlers here, not assuming they exist.
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

        // getConstraintViolations() is a Set with no defined iteration order, so a request that
        // breaks two constraints would otherwise report them in a different order each time.
        List<ApiError.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(violation -> new ApiError.FieldError(fieldName(violation), violation.getMessage()))
                .sorted(Comparator.comparing(ApiError.FieldError::field).thenComparing(ApiError.FieldError::message))
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

    /**
     * A malformed request: a blank required field, an out-of-range value, an unsupported
     * enumerated value, or a search query shorter than its inferred type allows. The request's
     * shape is wrong, so this is 400 per ADR-0017 §1 (issue #1694). The {@code VALIDATION_ERROR}
     * code is unchanged from the blanket {@code IllegalArgumentException} handler this type
     * replaces.
     */
    @ExceptionHandler(VehicleValidationException.class)
    public ResponseEntity<ApiError> handleValidation(
            VehicleValidationException ex, HttpServletRequest request, HttpServletResponse response) {
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
     * A create request is well-formed but its VIN is already held by another active vehicle: a
     * stateful collision, not a malformed request, so this is 409 per ADR-0017 §2 (issue #1694).
     * Previously reported as 400 {@code VALIDATION_ERROR} through the module's blanket
     * {@code IllegalArgumentException} handler.
     */
    @ExceptionHandler(VehicleVinConflictException.class)
    public ResponseEntity<ApiError> handleVinConflict(
            VehicleVinConflictException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("VIN conflict on {}: {}", path(request), ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        "VEHICLE_VIN_CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
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
