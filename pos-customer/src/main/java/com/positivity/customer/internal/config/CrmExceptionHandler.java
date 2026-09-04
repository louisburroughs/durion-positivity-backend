package com.positivity.customer.internal.config;

import com.positivity.customer.internal.exception.CrmDuplicateResourceException;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmTooManyRequestsException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.exception.CrmValidationException;
import com.positivity.customer.internal.exception.DuplicateRedemptionException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Global exception handler for CRM (pos-customer) REST controllers.
 *
 * <p>
 * Handled exceptions:
 * <ul>
 * <li>{@link com.positivity.customer.internal.exception.CrmValidationException} - 400 Bad
 * Request (malformed request / field validation)</li>
 * <li>{@link DuplicateRedemptionException} - 409 Conflict</li>
 * <li>{@link CrmDuplicateResourceException} - 409 Conflict</li>
 * <li>{@link MethodArgumentNotValidException} - 400 Bad Request
 * (validation)</li>
 * <li>{@link AccessDeniedException} - 403 Forbidden</li>
 * <li>{@link CrmResourceNotFoundException} - 404 Not Found</li>
 * <li>{@link CrmUnprocessableEntityException} - 422 Unprocessable Entity (domain policy)</li>
 * <li>{@link CrmTooManyRequestsException} - 429 Too Many Requests</li>
 * </ul>
 *
 * <p>
 * Deliberately NOT handled here: bare {@link IllegalArgumentException}. Hibernate/JPA throw
 * it for an invalid query, and {@code UUID.fromString} throws it on malformed stored data —
 * neither is this module's own validation failure, and a blanket handler that echoed
 * {@code exception.getMessage()} for it leaked internal class names and JPQL text to the
 * client as a spurious 4xx (issue #1694). An unmapped {@code IllegalArgumentException} (or any
 * other unexpected {@code RuntimeException}) now falls through to pos-web-common's
 * {@link com.positivity.web.common.GlobalApiExceptionHandler}, which answers a generic,
 * correlated 500 {@code INTERNAL_ERROR} and logs the stack trace — and which also restores
 * ADR-0056 §2's {@code DataIntegrityViolationException} mapping (409 unique/FK, 422
 * client-supplied not-null/check) that a module-local catch-all would otherwise shadow.
 */
@ControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class CrmExceptionHandler {
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request, HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Access denied on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        "PERMISSION_DENIED",
                        "You do not have permission to perform this action",
                        HttpStatus.FORBIDDEN.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(DuplicateRedemptionException.class)
    public ResponseEntity<ApiError> handleDuplicateRedemption(
            DuplicateRedemptionException ex, HttpServletRequest request, HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Duplicate redemption on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        "DUPLICATE_REDEMPTION",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(CrmResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            CrmResourceNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Resource not found on {}: {}", path, ex.getMessage());
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

    @ExceptionHandler(CrmDuplicateResourceException.class)
    public ResponseEntity<ApiError> handleDuplicateResource(
            CrmDuplicateResourceException ex, HttpServletRequest request, HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Duplicate resource on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        "DUPLICATE_RESOURCE",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * A structurally valid request whose content the domain refuses — currently the
     * segment predicate validator (Story #1137). ADR-0017 maps this to {@code 422}
     * so callers can distinguish it from a malformed body ({@code 400}).
     */
    @ExceptionHandler(CrmUnprocessableEntityException.class)
    public ResponseEntity<ApiError> handleUnprocessable(
            CrmUnprocessableEntityException ex, HttpServletRequest request, HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Unprocessable entity on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiError.of(
                        "UNPROCESSABLE_CONTENT",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * Rate limit exceeded on the public inquiry form. The message is deliberately
     * generic — a
     * response that revealed the exact limit or remaining quota would just tell an
     * abuser how
     * to pace themselves.
     */
    @ExceptionHandler(CrmTooManyRequestsException.class)
    public ResponseEntity<ApiError> handleTooManyRequests(
            CrmTooManyRequestsException ex, HttpServletRequest request, HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Rate limit exceeded on {}", path);
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiError.of(
                        "TOO_MANY_REQUESTS",
                        ex.getMessage(),
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request, HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Validation failed on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.withFieldErrors(
                        "VALIDATION_FAILED",
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors));
    }

    /**
     * A malformed request or module-level field-validation failure — never a bare
     * {@link IllegalArgumentException} (see class javadoc). Keeps the {@code VALIDATION_ERROR}
     * code the previous blanket handler used, so the wire contract for genuine client errors
     * does not drift.
     */
    @ExceptionHandler(CrmValidationException.class)
    public ResponseEntity<ApiError> handleValidation(
            CrmValidationException ex, HttpServletRequest request, HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Invalid argument on {}: {}", path, ex.getMessage());
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
