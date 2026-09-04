package com.positivity.marketing.internal.config;

import com.positivity.marketing.internal.exception.MarketingDuplicateResourceException;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
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
 * Global exception handler for pos-marketing REST controllers (ADR-0017).
 *
 * <p>Deliberately does NOT map bare {@code IllegalArgumentException} (issue #1694): that type is
 * not exclusive to this module's own throws — Hibernate/JPA throw it for an invalid query, {@code
 * UUID.fromString} throws it on malformed stored data, and a JPA attribute converter throws it on
 * corrupt stored JSON. This module's own services and controllers never throw it (every genuine
 * client error already has its own type — {@link MarketingResourceNotFoundException}, {@link
 * MarketingDuplicateResourceException}, {@link MarketingUnprocessableEntityException} — or is
 * bean-validation handled below), so a blanket handler that caught it existed purely to catch
 * faults from elsewhere and misreport them as 400s, leaking internal class names and query text.
 * An unexpected {@code IllegalArgumentException} now falls through to {@code pos-web-common}'s
 * platform-wide {@code GlobalApiExceptionHandler}, which answers a generic, correlated 500
 * instead of echoing the exception's own text.
 */
@ControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class MarketingExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Access denied on {}: {}", path(request), ex.getMessage());
        return error(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "PERMISSION_DENIED",
                "You do not have permission to perform this action");
    }

    @ExceptionHandler(MarketingResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            MarketingResourceNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Resource not found on {}: {}", path(request), ex.getMessage());
        return error(request, response, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(MarketingDuplicateResourceException.class)
    public ResponseEntity<ApiError> handleDuplicate(
            MarketingDuplicateResourceException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Duplicate resource on {}: {}", path(request), ex.getMessage());
        return error(request, response, HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", ex.getMessage());
    }

    @ExceptionHandler(MarketingUnprocessableEntityException.class)
    public ResponseEntity<ApiError> handleUnprocessable(
            MarketingUnprocessableEntityException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Unprocessable entity on {}: {}", path(request), ex.getMessage());
        return error(request, response, HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE_ENTITY", ex.getMessage());
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
                        "VALIDATION_FAILED",
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors));
    }

    private ResponseEntity<ApiError> error(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String message) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        return ResponseEntity.status(status)
                .body(ApiError.of(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId));
    }

    private static String path(HttpServletRequest request) {
        return request != null ? request.getRequestURI() : "";
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String correlationId = request.getHeader(X_CORRELATION_ID);
        return correlationId == null || correlationId.isBlank()
                ? UUIDv7Generator.generate().toString()
                : correlationId;
    }
}
