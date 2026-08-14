package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogForbiddenOperationException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.positivity.catalog.internal.controller")
@RequiredArgsConstructor
public class CatalogExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(CatalogNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(CatalogNotFoundException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), correlationId);
    }

    @ExceptionHandler(CatalogForbiddenOperationException.class)
    public ResponseEntity<ApiError> handleForbidden(CatalogForbiddenOperationException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return buildResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), correlationId);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("Validation failed");
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, correlationId);
    }

    /**
     * A missing or unparseable request parameter is a caller error, not a server fault. Without
     * these two handlers Spring's own exceptions fell through to the catch-all and every such
     * request answered 500 — which tells a client to retry something that will never succeed, and
     * hides a contract mistake behind an alarm.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Required parameter '" + ex.getParameterName() + "' is missing",
                correlationId);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        // The rejected value is echoed deliberately: a caller debugging a malformed UUID or date
        // needs to see what the server read, and it is their own input.
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Parameter '" + ex.getName() + "' has an invalid value: " + ex.getValue(),
                correlationId);
    }

    /**
     * Bean-validation failures on {@code @Validated} controller parameters — a page size beyond its
     * bound, for instance. Distinct from {@link MethodArgumentNotValidException}, which covers
     * request bodies, and previously unhandled, so bounded parameters answered 500 for violating
     * the bound the contract advertises.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> lastNode(violation) + " " + violation.getMessage())
                .orElse("Validation failed");
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, correlationId);
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }

    @ExceptionHandler(CatalogValidationException.class)
    public ResponseEntity<ApiError> handleBadRequest(CatalogValidationException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), correlationId);
    }

    @ExceptionHandler(CatalogBusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessConflict(
            CatalogBusinessRuleException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return buildResponse(HttpStatus.CONFLICT, "BUSINESS_RULE_VIOLATION", ex.getMessage(), correlationId);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiError> handleConflict(RuntimeException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return buildResponse(
                HttpStatus.CONFLICT, "CONFLICT", "Resource was updated concurrently. Please retry.", correlationId);
    }

    @ExceptionHandler(ServletException.class)
    public ResponseEntity<ApiError> handleServletException(ServletException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        Throwable cause = ex.getCause();
        if (cause instanceof OptimisticLockException || cause instanceof ObjectOptimisticLockingFailureException) {
            return buildResponse(
                    HttpStatus.CONFLICT, "CONFLICT", "Resource was updated concurrently. Please retry.", correlationId);
        }
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage(), correlationId);
    }

    private ResponseEntity<ApiError> buildResponse(
            HttpStatus status, String code, String message, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId),
                headers,
                status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}
