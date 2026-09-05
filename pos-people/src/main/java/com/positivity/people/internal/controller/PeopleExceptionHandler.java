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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
 * Module-local exception advice for pos-people.
 *
 * <p>#1716: thirteen of these handlers answered Spring's bare
 * {@link org.springframework.http.ProblemDetail} and a fourteenth an ad-hoc {@code Map} — three
 * error shapes in one advice, none carrying a {@code code}, and none carrying a correlation id
 * at all, so a failure could not be tied to its log entry. ADR-0017 §3 makes the {@link ApiError}
 * envelope the contract for every non-2xx body and §4 requires the correlation id in both the
 * body and the {@code X-Correlation-Id} header. #1694 had converted only the two handlers it
 * added ({@code RequestValidationException}, {@code ResourceStateConflictException}), leaving
 * this advice internally inconsistent — which is the drift ADR-0056's "Alternatives Considered"
 * calls out by name. Every handler now routes through the same {@code buildResponse}, so a new
 * one cannot quietly reintroduce a second shape. Statuses are unchanged throughout.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class PeopleExceptionHandler {

    private final Clock clock;

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String VALIDATION_FAILED_MESSAGE = "Validation failed";
    private static final String NOT_FOUND = "NOT_FOUND";

    @ExceptionHandler(PersonNotFoundException.class)
    public ResponseEntity<ApiError> handlePersonNotFound(
            PersonNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(HttpStatus.NOT_FOUND, "PERSON_NOT_FOUND", ex.getMessage(), request, response);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            NotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage(), request, response);
    }

    @ExceptionHandler(WorkSessionNotFoundException.class)
    public ResponseEntity<ApiError> handleWorkSessionNotFound(
            WorkSessionNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(HttpStatus.NOT_FOUND, "WORK_SESSION_NOT_FOUND", ex.getMessage(), request, response);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleEntityNotFound(
            EntityNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage(), request, response);
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
        return buildResponse(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage(), request, response);
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
        return buildResponse(HttpStatus.CONFLICT, "RESOURCE_STATE_CONFLICT", ex.getMessage(), request, response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(HttpStatus.CONFLICT, "INVALID_STATE", ex.getMessage(), request, response);
    }

    @ExceptionHandler(SemanticValidationException.class)
    public ResponseEntity<ApiError> handleSemanticValidation(
            SemanticValidationException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(
                HttpStatus.UNPROCESSABLE_CONTENT, "SEMANTIC_VALIDATION_ERROR", ex.getMessage(), request, response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), request, response);
    }

    // Without these handlers, Spring MVC's routing/binding exceptions fall through to
    // pos-web-common's platform-wide catch-all and every unknown path or malformed parameter
    // surfaces as a 500 (issue #820). The messages deliberately do not echo request data (path,
    // parameter values): reflecting user-controlled input is flagged as XSS-prone (SonarCloud
    // S5131). The parameter NAME is not user-controlled — Spring only raises these for
    // parameters the handler method declares — so naming it is safe and is what makes the
    // message actionable.
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNoEndpoint(HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(
                HttpStatus.NOT_FOUND, "NO_ENDPOINT", "No endpoint for the requested path", request, response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                VALIDATION_ERROR,
                "Invalid value for parameter '" + ex.getName() + "'",
                request,
                response);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                VALIDATION_ERROR,
                "Missing required parameter '" + ex.getParameterName() + "'",
                request,
                response);
    }

    /**
     * The message stays the fixed "Validation failed" and {@code fieldErrors} is deliberately
     * left empty. #1716 changes the envelope, not this trade: the binding result names this
     * module's internal property names, and this response is provokable by any caller. Modules
     * with no such constraint (pos-bulk-loader, pos-mcp-server) do populate {@code fieldErrors}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, VALIDATION_FAILED_MESSAGE, request, response);
    }

    // @Validated query/path parameter constraints (e.g. @PositiveOrZero page, @Max(100) size)
    // fail with this rather than MethodArgumentNotValidException, which only covers @Valid
    // request bodies.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, VALIDATION_FAILED_MESSAGE, request, response);
    }

    /**
     * #1716: this answered an ad-hoc {@code Map} with {@code error}/{@code path} keys — a third
     * error shape in the same advice. The request path is dropped rather than echoed, for the
     * XSS reason above.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request, HttpServletResponse response) {
        return buildResponse(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Malformed JSON request", request, response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatusException(
            ResponseStatusException ex, HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() == null ? ex.getMessage() : ex.getReason();
        return buildResponse(status, status.name(), message, request, response);
    }

    // No @ExceptionHandler(Exception.class) here (issue #1694): Spring's
    // ExceptionHandlerExceptionResolver picks the first applicable advice bean that has ANY
    // matching handler method, so a blanket catch-all in this module-local advice would swallow
    // every unmapped exception and prevent pos-web-common's platform-wide
    // GlobalApiExceptionHandler from ever running for this module. Anything not handled above now
    // falls through to that shared advice, which answers a generic, correlated 500 INTERNAL_ERROR,
    // logs the stack trace at ERROR, and maps DataIntegrityViolationException to 409/422 per
    // ADR-0056 §2.

    private ResponseEntity<ApiError> buildResponse(
            HttpStatus status, String code, String message, HttpServletRequest request, HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        code,
                        message != null ? message : status.getReasonPhrase(),
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String rawCorrelationId = request.getHeader(X_CORRELATION_ID);
        if (rawCorrelationId == null || rawCorrelationId.isBlank()) {
            return UUIDv7Generator.generate().toString();
        }
        return rawCorrelationId.trim();
    }
}
