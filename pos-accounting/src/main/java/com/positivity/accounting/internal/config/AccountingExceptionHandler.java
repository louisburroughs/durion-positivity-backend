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
import com.positivity.accounting.internal.dto.EnvelopeErrorResponse;
import com.positivity.accounting.internal.dto.ErrorResponse;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;

/**
 * Standardized error responses for security-related exceptions.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class AccountingExceptionHandler {
    private final Clock clock;

    @ExceptionHandler({ AuthenticationException.class, AuthenticationCredentialsNotFoundException.class })
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex) {
        ErrorResponse body = ErrorResponse.builder()
                .errorCode("UNAUTHENTICATED")
                .message("Authentication required")
                .timestamp(Instant.now(clock).toEpochMilli())
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse body = ErrorResponse.builder()
                .errorCode("FORBIDDEN")
                .message("Access denied")
                .timestamp(Instant.now(clock).toEpochMilli())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(DuplicateEventException.class)
    public ResponseEntity<EnvelopeErrorResponse> handleDuplicateEvent(DuplicateEventException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(EnvelopeErrorResponse.of("DUPLICATE_EVENT", ex.getMessage(), Instant.now(clock).toEpochMilli()));
    }

    @ExceptionHandler(UnbalancedEntryException.class)
    public ResponseEntity<EnvelopeErrorResponse> handleUnbalancedEntry(UnbalancedEntryException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(EnvelopeErrorResponse.of("UNBALANCED_ENTRY", ex.getMessage(), Instant.now(clock).toEpochMilli()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<EnvelopeErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String fieldName = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField())
                .orElse("unknown");
        String message = fieldName + " is required";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(EnvelopeErrorResponse.of("ARGUMENT_NOT_VALID", message, Instant.now(clock).toEpochMilli()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<EnvelopeErrorResponse> handleIllegalState(IllegalStateException ex) {
        String code = resolveStateErrorCode(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(EnvelopeErrorResponse.of(code, ex.getMessage(), Instant.now(clock).toEpochMilli()));
    }

    @ExceptionHandler(DuplicateAccountCodeException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateAccountCode(DuplicateAccountCodeException ex) {
        ErrorResponse body = ErrorResponse.builder()
                .errorCode("DUPLICATE_ACCOUNT_CODE")
                .message(ex.getMessage())
                .timestamp(Instant.now(clock).toEpochMilli())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        ErrorResponse body = ErrorResponse.builder()
                .errorCode("REQUEST_FAILED")
                .message(message)
                .timestamp(Instant.now(clock).toEpochMilli())
                .build();
        return ResponseEntity.status(ex.getStatusCode()).body(body);
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
