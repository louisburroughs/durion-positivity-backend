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

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class PeopleExceptionHandler {

    private final Clock clock;

    private static final String TIMESTAMP_PROPERTY = "timestamp";

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler(PersonNotFoundException.class)
    public ProblemDetail handlePersonNotFound(PersonNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(WorkSessionNotFoundException.class)
    public ProblemDetail handleWorkSessionNotFound(WorkSessionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleEntityNotFound(EntityNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
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
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        "RESOURCE_STATE_CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(SemanticValidationException.class)
    public ProblemDetail handleSemanticValidation(SemanticValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    // Without these handlers, Spring MVC's routing/binding exceptions fall through to the
    // Exception catch-all below and every unknown path or malformed parameter surfaces as a
    // 500 (issue #820). The details deliberately do not echo request data (path, parameter
    // values): reflecting user-controlled input is flagged as XSS-prone (SonarCloud S5131),
    // and the request path is already available in the ProblemDetail `instance` field.
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail handleNoEndpoint() {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No endpoint for the requested path");
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'");
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Missing required parameter '" + ex.getParameterName() + "'");
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    // @Validated query/path parameter constraints (e.g. @PositiveOrZero page, @Max(100) size)
    // fail with this rather than MethodArgumentNotValidException, which only covers @Valid
    // request bodies.
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP_PROPERTY, Instant.now(clock));
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("path", request.getRequestURI());
        body.put("message", "Malformed JSON request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(ResponseStatusException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(ex.getStatusCode().value()),
                ex.getReason() == null ? ex.getMessage() : ex.getReason());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    // No @ExceptionHandler(Exception.class) here (issue #1694): Spring's
    // ExceptionHandlerExceptionResolver picks the first applicable advice bean that has ANY
    // matching handler method, so a blanket catch-all in this module-local advice would swallow
    // every unmapped exception and prevent pos-web-common's platform-wide
    // GlobalApiExceptionHandler from ever running for this module. Anything not handled above now
    // falls through to that shared advice, which answers a generic, correlated 500 INTERNAL_ERROR,
    // logs the stack trace at ERROR, and maps DataIntegrityViolationException to 409/422 per
    // ADR-0056 §2.

    private String resolveCorrelationId(HttpServletRequest request) {
        String rawCorrelationId = request.getHeader(X_CORRELATION_ID);
        if (rawCorrelationId == null || rawCorrelationId.isBlank()) {
            return UUIDv7Generator.generate().toString();
        }
        return rawCorrelationId.trim();
    }
}
