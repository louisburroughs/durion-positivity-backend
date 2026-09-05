package com.positivity.people.internal.controller;

import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.exception.ResourceStateConflictException;
import com.positivity.people.internal.exception.SemanticValidationException;
import com.positivity.people.internal.exception.WorkSessionNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every response built by this advice carries the correlation id in both the response body (the
 * {@code correlationId} extension property on {@link ProblemDetail} responses, or the {@link
 * ApiError#correlationId()} field) and the {@code X-Correlation-Id} response header (ADR-0017
 * §4, issue #1729). {@link #problem} and {@link #apiError} are the only two paths that build a
 * response body in this advice, so a handler added later cannot forget the header.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class PeopleExceptionHandler {

    private final Clock clock;

    private static final String TIMESTAMP_PROPERTY = "timestamp";

    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler(PersonNotFoundException.class)
    public ProblemDetail handlePersonNotFound(
            PersonNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), request, response);
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(
            NotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), request, response);
    }

    @ExceptionHandler(WorkSessionNotFoundException.class)
    public ProblemDetail handleWorkSessionNotFound(
            WorkSessionNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), request, response);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleEntityNotFound(
            EntityNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), request, response);
    }

    /**
     * Genuine client input-validation failures raised by this module's own controllers/services
     * (see {@link RequestValidationException}). This class deliberately does NOT map bare {@code
     * IllegalArgumentException} (issue #1694): that type is not exclusive to this module's own
     * validation — Hibernate/JPA throw it for an invalid query and {@code UUID.fromString} throws
     * it on malformed stored data, and catching it here previously turned a server-side defect
     * into a client-facing 400 that also leaked internal class names and query text. An
     * unexpected {@code IllegalArgumentException} now falls through to {@code pos-web-common}'s
     * platform-wide {@code GlobalApiExceptionHandler} fallback, which answers a generic,
     * correlated 500 instead of echoing the exception text.
     */
    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ApiError> handleRequestValidation(
            RequestValidationException ex, HttpServletRequest request, HttpServletResponse response) {
        return apiError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request, response);
    }

    /**
     * Stateful collisions raised by this module's own controllers/services (see {@link
     * ResourceStateConflictException}): the request is well-formed, but the resource's current
     * status blocks the requested transition. This class deliberately does NOT route these
     * guards through bare {@code IllegalStateException} (issue #1694 follow-up): that type is
     * not exclusive to a lifecycle guard — {@code SecurityContextHelper} throws it for a missing
     * security context (a server-side/auth defect), and the JDK/Spring throw it for unrelated
     * misuse — so mapping it broadly would have only moved the #1694 bug class, not removed it.
     * The pre-existing {@link #handleIllegalState} mapping is left untouched for whatever else
     * still reaches it.
     */
    @ExceptionHandler(ResourceStateConflictException.class)
    public ResponseEntity<ApiError> handleResourceStateConflict(
            ResourceStateConflictException ex, HttpServletRequest request, HttpServletResponse response) {
        return apiError(HttpStatus.CONFLICT, "RESOURCE_STATE_CONFLICT", ex.getMessage(), request, response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(
            IllegalStateException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), request, response);
    }

    @ExceptionHandler(SemanticValidationException.class)
    public ProblemDetail handleSemanticValidation(
            SemanticValidationException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), request, response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage(), request, response);
    }

    // Without these handlers, Spring MVC's routing/binding exceptions fall through to the
    // Exception catch-all below and every unknown path or malformed parameter surfaces as a
    // 500 (issue #820). The details deliberately do not echo request data (path, parameter
    // values): reflecting user-controlled input is flagged as XSS-prone (SonarCloud S5131),
    // and the request path is already available in the ProblemDetail `instance` field.
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail handleNoEndpoint(HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.NOT_FOUND, "No endpoint for the requested path", request, response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'", request, response);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Missing required parameter '" + ex.getParameterName() + "'",
                request,
                response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", request, response);
    }

    // @Validated query/path parameter constraints (e.g. @PositiveOrZero page, @Max(100) size)
    // fail with this rather than MethodArgumentNotValidException, which only covers @Valid
    // request bodies.
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", request, response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request, HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP_PROPERTY, Instant.now(clock));
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("path", request.getRequestURI());
        body.put("message", "Malformed JSON request");
        body.put(CORRELATION_ID_PROPERTY, correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(
            ResponseStatusException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(
                HttpStatus.valueOf(ex.getStatusCode().value()),
                ex.getReason() == null ? ex.getMessage() : ex.getReason(),
                request,
                response);
    }

    // No @ExceptionHandler(Exception.class) here (issue #1694): Spring's
    // ExceptionHandlerExceptionResolver picks the first applicable advice bean that has ANY
    // matching handler method, so a blanket catch-all in this module-local advice would swallow
    // every unmapped exception and prevent pos-web-common's platform-wide
    // GlobalApiExceptionHandler from ever running for this module. Anything not handled above now
    // falls through to that shared advice, which answers a generic, correlated 500 INTERNAL_ERROR,
    // logs the stack trace at ERROR, and maps DataIntegrityViolationException to 409/422 per
    // ADR-0056 §2.

    /**
     * Builds the standardized {@link ProblemDetail} response, carrying the correlation id in
     * both a {@code correlationId} extension property on the body and the {@code
     * X-Correlation-Id} response header (ADR-0017 §4, issue #1729). One of the two paths in this
     * advice that build a response body — the other is {@link #apiError} — so a handler added
     * later cannot forget the header.
     */
    private ProblemDetail problem(
            HttpStatus status, String detail, HttpServletRequest request, HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        problemDetail.setProperty(CORRELATION_ID_PROPERTY, correlationId);
        return problemDetail;
    }

    /**
     * Builds the standardized {@link ApiError} response, carrying the correlation id in both the
     * body and the {@code X-Correlation-Id} response header (ADR-0017 §4, issue #1729). One of
     * the two paths in this advice that build a response body — the other is {@link #problem} —
     * so a handler added later cannot forget the header.
     */
    private ResponseEntity<ApiError> apiError(
            HttpStatus status, String code, String message, HttpServletRequest request, HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        return ResponseEntity.status(status)
                .body(ApiError.of(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId));
    }

    /**
     * Extracts correlation ID from request header or generates new one.
     *
     * <p>Priority:
     *
     * <ol>
     *   <li>{@code X-Correlation-Id} header (if present and non-blank)
     *   <li>Generate new UUID v7 (see {@link UUIDv7Generator})
     * </ol>
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        String rawCorrelationId = request.getHeader(X_CORRELATION_ID);
        if (rawCorrelationId == null || rawCorrelationId.isBlank()) {
            return UUIDv7Generator.generate().toString();
        }
        return rawCorrelationId.trim();
    }
}
