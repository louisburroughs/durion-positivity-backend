package com.positivity.customer.internal.config;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.positivity.customer.internal.exception.DuplicateRedemptionException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler for CRM (pos-customer) REST controllers.
 *
 * <p>
 * Handled exceptions:
 * <ul>
 * <li>{@link IllegalArgumentException} - 400 Bad Request (domain
 * validation)</li>
 * <li>{@link DuplicateRedemptionException} - 409 Conflict</li>
 * <li>{@link MethodArgumentNotValidException} - 400 Bad Request
 * (validation)</li>
 * <li>{@link AccessDeniedException} - 403 Forbidden</li>
 * </ul>
 */
@ControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class CrmExceptionHandler {
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Access denied on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("PERMISSION_DENIED",
                        "You do not have permission to perform this action",
                        HttpStatus.FORBIDDEN.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(DuplicateRedemptionException.class)
    public ResponseEntity<ApiError> handleDuplicateRedemption(
            DuplicateRedemptionException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Duplicate redemption on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("DUPLICATE_REDEMPTION", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Validation failed on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        List<ApiError.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ApiError.FieldError(fe.getField(),
                        fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.withFieldErrors("VALIDATION_FAILED", "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(), Instant.now(clock).toString(), correlationId, fieldErrors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Invalid argument on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_ERROR", ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(), Instant.now(clock).toString(), correlationId));
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

