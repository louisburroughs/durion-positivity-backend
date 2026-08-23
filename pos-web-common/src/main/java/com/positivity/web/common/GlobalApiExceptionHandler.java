package com.positivity.web.common;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.web.common.DataIntegrityViolations.Classification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Platform fallback exception handler: guarantees that no exception leaves a service as
 * Spring's bare default error page (issue #1471). Every response it produces carries the
 * canonical {@link ApiError} envelope and echoes/generates {@code X-Correlation-Id}, as
 * ADR-0017 §3/§4 require.
 *
 * <p>Registered at {@link Ordered#LOWEST_PRECEDENCE}, so any service-specific
 * {@code @ControllerAdvice} that maps an exception type keeps precedence; this advice only
 * sees what no other advice handled.
 *
 * <p>Response bodies stay generic on 500s — the correlation id, logged with the stack trace
 * at ERROR, is what makes the failure diagnosable. Constraint and column identifiers may be
 * named on 409/422; rejected data values are never echoed back.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    /**
     * Spring Security exceptions must reach the security filter chain's entry point /
     * access-denied handler, not this advice. Resolved reflectively because pos-web-common
     * does not depend on Spring Security, while most consuming services have it.
     */
    private static final List<Class<?>> SECURITY_EXCEPTIONS = loadSecurityExceptionTypes();

    private final Clock clock;

    public GlobalApiExceptionHandler(@NonNull Clock clock) {
        this.clock = clock;
    }

    /**
     * Maps database integrity failures per ADR-0017 §2: {@code 409} for unique and
     * foreign-key collisions, {@code 422} for not-null/check violations on client-supplied
     * values. A not-null violation on a server-populated audit column (for example
     * {@code created_by} with no {@code AuditorAware}, issue #1464) is a server defect and
     * stays a {@code 500} — but an enveloped, correlated one.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            @NonNull DataIntegrityViolationException ex,
            @Nullable HttpServletRequest request,
            @NonNull HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        String path = request != null ? request.getRequestURI() : "";
        Classification classification = DataIntegrityViolations.classify(ex);

        return switch (classification.kind()) {
            case UNIQUE -> {
                log.warn(
                        "Unique constraint violation on {} [correlationId={}]: constraint={}",
                        path,
                        correlationId,
                        classification.constraintName());
                yield envelope(
                        HttpStatus.CONFLICT,
                        "DUPLICATE_RESOURCE",
                        withIdentifier("Duplicate value violates unique constraint", classification.constraintName()),
                        correlationId);
            }
            case NOT_NULL -> {
                if (DataIntegrityViolations.isServerPopulatedAuditColumn(classification.columnName())) {
                    log.error(
                            "Not-null violation on server-populated column '{}' on {} [correlationId={}] — "
                                    + "server-side defect (e.g. missing AuditorAware), not a client error",
                            classification.columnName(),
                            path,
                            correlationId,
                            ex);
                    yield internalError(correlationId);
                }
                log.warn(
                        "Not-null constraint violation on {} [correlationId={}]: column={}",
                        path,
                        correlationId,
                        classification.columnName());
                yield envelope(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "MISSING_REQUIRED_VALUE",
                        withIdentifier("Required value is missing for column", classification.columnName()),
                        correlationId);
            }
            case CHECK -> {
                log.warn(
                        "Check constraint violation on {} [correlationId={}]: constraint={}",
                        path,
                        correlationId,
                        classification.constraintName());
                yield envelope(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "CONSTRAINT_VIOLATION",
                        withIdentifier("Value violates check constraint", classification.constraintName()),
                        correlationId);
            }
            case FOREIGN_KEY -> {
                log.warn(
                        "Foreign-key constraint violation on {} [correlationId={}]: constraint={}",
                        path,
                        correlationId,
                        classification.constraintName());
                yield envelope(
                        HttpStatus.CONFLICT,
                        "REFERENCE_CONFLICT",
                        withIdentifier(
                                "Operation conflicts with referential constraint", classification.constraintName()),
                        correlationId);
            }
            case OTHER_INTEGRITY -> {
                log.warn(
                        "Data integrity violation on {} [correlationId={}]: constraint={}",
                        path,
                        correlationId,
                        classification.constraintName());
                yield envelope(
                        HttpStatus.CONFLICT,
                        "DATA_INTEGRITY_VIOLATION",
                        "Operation violates a data integrity constraint",
                        correlationId);
            }
            case UNKNOWN -> {
                log.error("Unclassifiable data integrity violation on {} [correlationId={}]", path, correlationId, ex);
                yield internalError(correlationId);
            }
        };
    }

    /**
     * Bean-validation failures that no service advice mapped. Without this handler the
     * catch-all below would turn every {@code @Valid} failure into a 500.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex,
            @Nullable HttpServletRequest request,
            @NonNull HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiError.FieldError(
                        fieldError.getField(),
                        fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "Invalid value"))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.withFieldErrors(
                        "VALIDATION_ERROR",
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors));
    }

    /**
     * Unreadable request bodies (malformed JSON, invalid enum values). Unlike most Spring MVC
     * web exceptions this type does not implement {@link ErrorResponse}, so without this
     * handler the catch-all below would turn a client's 400 into a 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMessageNotReadable(
            @NonNull HttpMessageNotReadableException ex,
            @Nullable HttpServletRequest request,
            @NonNull HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        // Deliberately not logging ex.getMessage(): parser messages echo user-supplied body
        // content, and a routine client 400 is DEBUG-level noise. The correlation id is enough.
        log.debug(
                "Unreadable request body on {} [correlationId={}]",
                request != null ? request.getRequestURI() : "",
                correlationId);
        return envelope(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Malformed request body", correlationId);
    }

    /**
     * Parameter/path-variable conversion failures. {@link ConversionNotSupportedException}
     * means a missing server-side converter, so it stays a 500 via the catch-all; every other
     * type mismatch is the client's 400. Neither implements {@link ErrorResponse} on all
     * supported Spring lines, hence the explicit handler.
     */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            @NonNull TypeMismatchException ex,
            @Nullable HttpServletRequest request,
            @NonNull HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        String path = request != null ? request.getRequestURI() : "";
        if (ex instanceof ConversionNotSupportedException) {
            log.error("No converter for parameter on {} [correlationId={}]", path, correlationId, ex);
            return internalError(correlationId);
        }
        // Same reasoning as handleMessageNotReadable: the exception message echoes the
        // user-supplied value, and a client 400 is DEBUG-level noise.
        log.debug("Parameter type mismatch on {} [correlationId={}]", path, correlationId);
        return envelope(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid parameter value", correlationId);
    }

    /**
     * The catch-all ADR-0017 implies and issue #1471 makes explicit: whatever reaches here
     * leaves with the envelope and a correlation id, and the stack trace is logged at ERROR
     * against that id. Spring Security exceptions are rethrown so the filter chain renders
     * its 401/403; framework {@link ErrorResponse} exceptions keep their own status instead
     * of collapsing to 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnhandled(
            @NonNull Exception ex, @Nullable HttpServletRequest request, @NonNull HttpServletResponse response)
            throws Exception {
        if (isSecurityException(ex)) {
            throw ex;
        }
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        String path = request != null ? request.getRequestURI() : "";

        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            if (!status.is5xxServerError()) {
                log.warn(
                        "{} on {} [correlationId={}]: status={}",
                        ex.getClass().getSimpleName(),
                        path,
                        correlationId,
                        status.value());
                return envelope(status, frameworkErrorCode(status), frameworkErrorMessage(status), correlationId);
            }
        }

        log.error("Unhandled exception on {} [correlationId={}]", path, correlationId, ex);
        return internalError(correlationId);
    }

    private ResponseEntity<ApiError> internalError(String correlationId) {
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error occurred", correlationId);
    }

    private ResponseEntity<ApiError> envelope(
            HttpStatusCode status, String code, String message, String correlationId) {
        return ResponseEntity.status(status)
                .body(ApiError.of(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId));
    }

    /**
     * Codes/messages for Spring MVC's own {@link ErrorResponse} exceptions (unknown path,
     * unsupported method/media type, malformed request). Deliberately generic: the request
     * path and parameter values are user-controlled and must not be reflected (SonarCloud
     * S5131); the path is already in the request the client sent.
     */
    private static String frameworkErrorCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "VALIDATION_ERROR";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "REQUEST_REJECTED";
        };
    }

    private static String frameworkErrorMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Malformed request";
            case 404 -> "No endpoint for the requested path";
            case 405 -> "HTTP method not supported for this path";
            case 415 -> "Unsupported media type";
            default -> "Request rejected";
        };
    }

    private String resolveCorrelationId(@Nullable HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String correlationId = request.getHeader(X_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            return UUIDv7Generator.generate().toString();
        }
        return correlationId.trim();
    }

    private static String withIdentifier(String message, @Nullable String identifier) {
        return identifier == null ? message : message + " '" + identifier + "'";
    }

    private static boolean isSecurityException(Exception ex) {
        return SECURITY_EXCEPTIONS.stream().anyMatch(type -> type.isInstance(ex));
    }

    private static List<Class<?>> loadSecurityExceptionTypes() {
        return List.of(
                        "org.springframework.security.access.AccessDeniedException",
                        "org.springframework.security.core.AuthenticationException")
                .stream()
                .<Class<?>>mapMulti((name, consumer) -> {
                    try {
                        consumer.accept(Class.forName(name, false, GlobalApiExceptionHandler.class.getClassLoader()));
                    } catch (ClassNotFoundException ignored) {
                        // Spring Security not on the classpath: nothing to rethrow.
                    }
                })
                .toList();
    }
}
