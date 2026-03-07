package com.positivity.customer.internal.config;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.customer.internal.exception.DuplicateRedemptionException;
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
    private final Clock clock;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            String code,
            String message,
            int status,
            String timestamp,
            String correlationId,
            List<FieldErrorEnvelope> fieldErrors) {
    }

    public record FieldErrorEnvelope(String field, String message) {
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Access denied on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader("X-Correlation-Id", correlationId);

        ErrorResponse body = new ErrorResponse(
                "PERMISSION_DENIED",
                "You do not have permission to perform this action",
                HttpStatus.FORBIDDEN.value(),
                Instant.now(clock).toString(),
                correlationId,
                null);

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(DuplicateRedemptionException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateRedemption(
            DuplicateRedemptionException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Duplicate redemption on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader("X-Correlation-Id", correlationId);

        ErrorResponse body = new ErrorResponse(
                "DUPLICATE_REDEMPTION",
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                Instant.now(clock).toString(),
                correlationId,
                null);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        String path = request != null ? request.getRequestURI() : "";
        log.warn("Validation failed on {}: {}", path, ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader("X-Correlation-Id", correlationId);

        List<FieldErrorEnvelope> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldErrorEnvelope)
                .toList();

        ErrorResponse body = new ErrorResponse(
                "VALIDATION_FAILED",
                "Request validation failed",
                HttpStatus.BAD_REQUEST.value(),
                Instant.now(clock).toString(),
                correlationId,
                fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private FieldErrorEnvelope toFieldErrorEnvelope(FieldError fieldError) {
        return new FieldErrorEnvelope(
                fieldError.getField(),
                fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "Invalid value");
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            return UUIDv7Generator.generate().toString();
        }
        return correlationId;
    }

}
