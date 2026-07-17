package com.positivity.peoplecontact.internal.controller;

import com.positivity.peoplecontact.internal.client.SecurityServiceException;
import com.positivity.peoplecontact.internal.exception.NotFoundException;
import com.positivity.peoplecontact.internal.exception.PersonHasLinkedUsersException;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.exception.SemanticValidationException;
import com.positivity.peoplecontact.internal.exception.UserAlreadyLinkedException;
import com.positivity.peoplecontact.internal.exception.UserPersonLinkNotFoundException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
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

    @ExceptionHandler(PersonNotFoundException.class)
    public ProblemDetail handlePersonNotFound(PersonNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(UserPersonLinkNotFoundException.class)
    public ProblemDetail handleLinkNotFound(UserPersonLinkNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(PersonHasLinkedUsersException.class)
    public ProblemDetail handlePersonHasLinkedUsers(PersonHasLinkedUsersException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        problem.setProperty("nextAction", PersonHasLinkedUsersException.NEXT_ACTION);
        return problem;
    }

    @ExceptionHandler(UserAlreadyLinkedException.class)
    public ProblemDetail handleUserAlreadyLinked(UserAlreadyLinkedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
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

    @ExceptionHandler(SecurityServiceException.class)
    public ProblemDetail handleSecurityServiceException(SecurityServiceException ex) {
        HttpStatus status = determineHttpStatus(ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(ResponseStatusException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(ex.getStatusCode().value()),
                ex.getReason() == null ? ex.getMessage() : ex.getReason());
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex) {
        log.error("Unhandled exception in People API", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock));
        return problem;
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
