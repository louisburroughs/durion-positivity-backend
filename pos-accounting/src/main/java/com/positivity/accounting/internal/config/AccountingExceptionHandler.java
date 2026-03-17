package com.positivity.accounting.internal.config;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;
import com.positivity.shared.error.ApiError;

/**
 * Standardized error responses for security-related exceptions.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class AccountingExceptionHandler {
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final Clock clock;

    @ExceptionHandler({ AuthenticationException.class, AuthenticationCredentialsNotFoundException.class })
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(ApiError.of("UNAUTHENTICATED", "Authentication required",
                        HttpStatus.UNAUTHORIZED.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(ApiError.of("FORBIDDEN", "Access denied",
                        HttpStatus.FORBIDDEN.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(DuplicateEventException.class)
    public ResponseEntity<ApiError> handleDuplicateEvent(DuplicateEventException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(ApiError.of("DUPLICATE_EVENT", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(UnbalancedEntryException.class)
    public ResponseEntity<ApiError> handleUnbalancedEntry(UnbalancedEntryException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(ApiError.of("UNBALANCED_ENTRY", ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String fieldName = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField())
                .orElse("unknown");
        String message = fieldName + " is required";
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(ApiError.of("ARGUMENT_NOT_VALID", message,
                        HttpStatus.BAD_REQUEST.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        String code = resolveStateErrorCode(ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(ApiError.of(code, ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(DuplicateAccountCodeException.class)
    public ResponseEntity<ApiError> handleDuplicateAccountCode(DuplicateAccountCodeException ex,
            HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(ApiError.of("DUPLICATE_ACCOUNT_CODE", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), correlationId));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        int statusCode = ex.getStatusCode().value();
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(ex.getStatusCode())
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(ApiError.of("REQUEST_FAILED", message, statusCode, Instant.now(clock).toString(),
                        correlationId));
    }

    private String resolveStateErrorCode(String message) {
        if (message != null && message.startsWith("Cannot post POSTED")) {
            return "ENTRY_ALREADY_POSTED";
        }
        if (message != null && message.startsWith("Cannot post REVERSED")) {
            return "ENTRY_ALREADY_POSTED";
        }
        return "ILLEGAL_STATE";
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUID.randomUUID().toString();
        }

        Object attributeValue = request.getAttribute(CORRELATION_ID_HEADER);
        String correlationId = null;
        if (attributeValue instanceof String) {
            correlationId = (String) attributeValue;
        }

        if (correlationId == null || correlationId.isBlank()) {
            String headerValue = request.getHeader(CORRELATION_ID_HEADER);
            if (headerValue != null && !headerValue.isBlank()) {
                correlationId = headerValue;
            }
        }

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        return correlationId;
    }
}

