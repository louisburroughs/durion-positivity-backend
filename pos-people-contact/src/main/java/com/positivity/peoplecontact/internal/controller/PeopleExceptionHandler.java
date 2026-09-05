package com.positivity.peoplecontact.internal.controller;

import com.positivity.peoplecontact.internal.client.SecurityServiceException;
import com.positivity.peoplecontact.internal.exception.NotFoundException;
import com.positivity.peoplecontact.internal.exception.PeopleContactValidationException;
import com.positivity.peoplecontact.internal.exception.PersonHasLinkedUsersException;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.exception.SemanticValidationException;
import com.positivity.peoplecontact.internal.exception.UserAlreadyLinkedException;
import com.positivity.peoplecontact.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
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
 * Module-local exception advice for pos-people-contact.
 *
 * <p>#1716: every handler here answered Spring's bare {@link org.springframework.http.ProblemDetail}
 * until now — no {@code code}, and no correlation id at all, so a failure could not be tied to
 * its log entry. ADR-0017 §3 makes the {@link ApiError} envelope the contract for every non-2xx
 * body and §4 requires the correlation id in both the body and the {@code X-Correlation-Id}
 * header. #1694 had converted only the handler it touched
 * ({@code PeopleContactValidationException}), leaving this advice internally inconsistent — some
 * responses enveloped, most not — which is the drift ADR-0056's "Alternatives Considered" calls
 * out by name. Every handler now routes through the same {@code buildResponse}, so a new one
 * cannot quietly reintroduce a second shape. Statuses are unchanged throughout.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class PeopleExceptionHandler {

    private final Clock clock;

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String NOT_FOUND = "NOT_FOUND";

    @ExceptionHandler(PersonNotFoundException.class)
    public ResponseEntity<ApiError> handlePersonNotFound(PersonNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "PERSON_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(UserPersonLinkNotFoundException.class)
    public ResponseEntity<ApiError> handleLinkNotFound(UserPersonLinkNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "USER_PERSON_LINK_NOT_FOUND", ex.getMessage(), request);
    }

    /**
     * Keeps the recovery hint the {@code ProblemDetail} carried as an extension property; the
     * envelope has a first-class {@code nextAction} field for exactly this (ADR-0017 §3).
     */
    @ExceptionHandler(PersonHasLinkedUsersException.class)
    public ResponseEntity<ApiError> handlePersonHasLinkedUsers(
            PersonHasLinkedUsersException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.guided(
                        "PERSON_HAS_LINKED_USERS",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        null,
                        PersonHasLinkedUsersException.NEXT_ACTION,
                        null));
    }

    @ExceptionHandler(UserAlreadyLinkedException.class)
    public ResponseEntity<ApiError> handleUserAlreadyLinked(UserAlreadyLinkedException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, "USER_ALREADY_LINKED", ex.getMessage(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleEntityNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * Genuine client input-validation failures raised by this module's own controllers,
     * services, and {@link com.positivity.peoplecontact.internal.client.SecurityServiceClient}
     * (see {@link PeopleContactValidationException}). This class deliberately does NOT map bare
     * {@code IllegalArgumentException} (issue #1694): that type is not exclusive to this
     * module's validation — Hibernate/JPA throw it for an invalid query and {@code
     * UUID.fromString} throws it on malformed stored data, and catching it here would turn a
     * server-side defect into a client-facing 400 that leaks internal class names and query
     * text. An unexpected {@code IllegalArgumentException} now falls through to {@code
     * pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler} fallback, which answers
     * a generic, correlated 500 instead of echoing the exception's own message.
     */
    @ExceptionHandler(PeopleContactValidationException.class)
    public ResponseEntity<ApiError> handlePeopleContactValidation(
            PeopleContactValidationException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, "INVALID_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(SemanticValidationException.class)
    public ResponseEntity<ApiError> handleSemanticValidation(
            SemanticValidationException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.UNPROCESSABLE_CONTENT, "SEMANTIC_VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), request);
    }

    // Without these handlers, Spring MVC's routing/binding exceptions fall through to
    // pos-web-common's platform-wide catch-all and every unknown path or malformed parameter
    // surfaces as a generic 500 (issue #820). The messages deliberately do not echo request data
    // (path, parameter values): reflecting user-controlled input is flagged as XSS-prone
    // (SonarCloud S5131). The parameter NAME is not user-controlled — Spring only raises these
    // for parameters the handler method declares — so naming it is safe and is what makes the
    // message actionable.
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNoEndpoint(HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "NO_ENDPOINT", "No endpoint for the requested path", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                VALIDATION_ERROR,
                "Invalid value for parameter '" + ex.getName() + "'",
                request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                VALIDATION_ERROR,
                "Missing required parameter '" + ex.getParameterName() + "'",
                request);
    }

    /**
     * The message stays the fixed "Validation failed" and {@code fieldErrors} is deliberately
     * left empty. #1716 changes the envelope, not this trade: the binding result names this
     * module's internal property names, and this response is provokable by any caller, so
     * {@code PeopleExceptionHandlerTest.validationReportsFixedDetail} pins the omission on
     * purpose. Modules with no such constraint (pos-bulk-loader, pos-mcp-server) do populate
     * {@code fieldErrors}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Validation failed", request);
    }

    /**
     * #1716: this answered an ad-hoc {@code Map} with {@code error}/{@code path} keys — a third
     * error shape in the same advice. The request path is dropped rather than echoed, for the
     * XSS reason above.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Malformed JSON request", request);
    }

    @ExceptionHandler(SecurityServiceException.class)
    public ResponseEntity<ApiError> handleSecurityServiceException(
            SecurityServiceException ex, HttpServletRequest request) {
        HttpStatus status = determineHttpStatus(ex);
        return buildResponse(status, "SECURITY_SERVICE_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatusException(
            ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() == null ? ex.getMessage() : ex.getReason();
        return buildResponse(status, statusCodeName(status), message, request);
    }

    // No @ExceptionHandler(Exception.class) catch-all here (issue #1694): a module-local
    // blanket handler pre-empts pos-web-common's GlobalApiExceptionHandler, which is registered
    // at Ordered.LOWEST_PRECEDENCE specifically so any service-specific advice runs first and
    // it only sees what nothing else handled. Anything this advice does not map now falls
    // through to that platform fallback — a generic, correlated 500 INTERNAL_ERROR that never
    // echoes the exception's own message, with DataIntegrityViolationException mapped to
    // 409/422 per ADR-0056 §2.

    /** Derives a stable envelope code from a status Spring chose, e.g. 404 -> NOT_FOUND. */
    private static String statusCodeName(HttpStatus status) {
        return status.name();
    }

    private ResponseEntity<ApiError> buildResponse(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(
                        code,
                        message != null ? message : status.getReasonPhrase(),
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId),
                headers,
                status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }

    private HttpStatus determineHttpStatus(SecurityServiceException ex) {
        int statusCode = ex.getHttpStatus();

        return determineHttpStatus(statusCode);
    }

    private HttpStatus determineHttpStatus(int statusCode) {

        return switch (statusCode) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> {
                if (statusCode >= 500 && statusCode < 600) {
                    yield HttpStatus.INTERNAL_SERVER_ERROR;
                }
                if (statusCode >= 400 && statusCode < 500) {
                    yield HttpStatus.BAD_REQUEST;
                }
                yield HttpStatus.INTERNAL_SERVER_ERROR;
            }
        };
    }
}
