package com.positivity.accounting.internal.config;

import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.AccountingPeriodStateException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;
import com.positivity.accounting.internal.exception.PeriodCloseBlockedException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Standardized error responses for security-related exceptions.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class AccountingExceptionHandler {
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler({AuthenticationException.class, AuthenticationCredentialsNotFoundException.class})
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateEventException.class)
    public ResponseEntity<ApiError> handleDuplicateEvent(DuplicateEventException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_EVENT", ex.getMessage(), request);
    }

    @ExceptionHandler(UnbalancedEntryException.class)
    public ResponseEntity<ApiError> handleUnbalancedEntry(UnbalancedEntryException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "UNBALANCED_ENTRY", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String fieldName = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField())
                .orElse("unknown");
        String message = fieldName + " is required";
        return build(HttpStatus.BAD_REQUEST, "ARGUMENT_NOT_VALID", message, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        String code = resolveStateErrorCode(ex.getMessage());
        return build(HttpStatus.CONFLICT, code, ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateAccountCodeException.class)
    public ResponseEntity<ApiError> handleDuplicateAccountCode(
            DuplicateAccountCodeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_ACCOUNT_CODE", ex.getMessage(), request);
    }

    @ExceptionHandler(AccountingPeriodNotFoundException.class)
    public ResponseEntity<ApiError> handlePeriodNotFound(
            AccountingPeriodNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "PERIOD_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(AccountingPeriodStateException.class)
    public ResponseEntity<ApiError> handlePeriodStateConflict(
            AccountingPeriodStateException ex, HttpServletRequest request) {
        String code = ex.getCurrentStatus() == AccountingPeriodStatus.CLOSED
                ? "PERIOD_ALREADY_CLOSED"
                : "PERIOD_ALREADY_OPEN";
        return build(HttpStatus.CONFLICT, code, ex.getMessage(), request);
    }

    @ExceptionHandler(PeriodCloseBlockedException.class)
    public ResponseEntity<ApiError> handlePeriodCloseBlocked(
            PeriodCloseBlockedException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        List<ApiError.FieldError> fieldErrors = ex.getDraftJournalEntryIds().stream()
                .map(UUID::toString)
                .map(id -> new ApiError.FieldError("draftJournalEntryIds", id))
                .toList();
        return new ResponseEntity<>(
                ApiError.withFieldErrors(
                        "PERIOD_HAS_DRAFT_ENTRIES",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors),
                headers,
                HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        String correlationId = resolveCorrelationId(request);
        int statusCode = ex.getStatusCode().value();
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(
                        "REQUEST_FAILED",
                        message,
                        statusCode,
                        Instant.now(clock).toString(),
                        correlationId),
                headers,
                ex.getStatusCode());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId),
                headers,
                status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }

    private String resolveStateErrorCode(String message) {
        if (message != null && message.startsWith("Cannot post POSTED")) {
            return "ENTRY_ALREADY_POSTED";
        }
        if (message != null && message.startsWith("Cannot post REVERSED")) {
            return "ENTRY_ALREADY_POSTED";
        }
        if (message != null && message.contains("already PROCESSED")) {
            return "CONFLICT";
        }
        return "ILLEGAL_STATE";
    }
}
