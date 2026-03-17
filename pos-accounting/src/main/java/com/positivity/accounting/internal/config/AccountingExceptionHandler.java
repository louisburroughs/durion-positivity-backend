package com.positivity.accounting.internal.config;

import java.time.Clock;
import java.time.Instant;

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
    private final Clock clock;

    @ExceptionHandler({ AuthenticationException.class, AuthenticationCredentialsNotFoundException.class })
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("UNAUTHENTICATED", "Authentication required",
                        HttpStatus.UNAUTHORIZED.value(), Instant.now(clock).toString(), null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Access denied",
                        HttpStatus.FORBIDDEN.value(), Instant.now(clock).toString(), null));
    }

    @ExceptionHandler(DuplicateEventException.class)
    public ResponseEntity<ApiError> handleDuplicateEvent(DuplicateEventException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("DUPLICATE_EVENT", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), null));
    }

    @ExceptionHandler(UnbalancedEntryException.class)
    public ResponseEntity<ApiError> handleUnbalancedEntry(UnbalancedEntryException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiError.of("UNBALANCED_ENTRY", ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(), Instant.now(clock).toString(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String fieldName = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField())
                .orElse("unknown");
        String message = fieldName + " is required";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("ARGUMENT_NOT_VALID", message,
                        HttpStatus.BAD_REQUEST.value(), Instant.now(clock).toString(), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex) {
        String code = resolveStateErrorCode(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(code, ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), null));
    }

    @ExceptionHandler(DuplicateAccountCodeException.class)
    public ResponseEntity<ApiError> handleDuplicateAccountCode(DuplicateAccountCodeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("DUPLICATE_ACCOUNT_CODE", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now(clock).toString(), null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        int statusCode = ex.getStatusCode().value();
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiError.of("REQUEST_FAILED", message, statusCode, Instant.now(clock).toString(), null));
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
}

