package com.positivity.accounting.internal.config;

import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.AccountingPeriodStateException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;
import com.positivity.accounting.internal.exception.HardLockDateRegressionException;
import com.positivity.accounting.internal.exception.JournalEntryNotReversibleException;
import com.positivity.accounting.internal.exception.MultiApplicationReversalException;
import com.positivity.accounting.internal.exception.PeriodCloseBlockedException;
import com.positivity.accounting.internal.exception.ReceivablePaymentNotFoundException;
import com.positivity.accounting.internal.exception.SettlementLineNotFoundException;
import com.positivity.accounting.internal.exception.SettlementLineNotUnmatchedException;
import com.positivity.accounting.internal.exception.SettlementNotPostedException;
import com.positivity.accounting.internal.exception.SettlementWriteOffThresholdExceededException;
import com.positivity.accounting.internal.exception.UnbalancedRulesException;
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

    /**
     * Reversal state conflicts (story A3, issue #943): double reversal —
     * including a lost concurrent-reversal race — maps to JE_ALREADY_REVERSED,
     * reversing a DRAFT/PENDING entry to JE_NOT_POSTED; both 409.
     */
    @ExceptionHandler(JournalEntryNotReversibleException.class)
    public ResponseEntity<ApiError> handleNotReversible(
            JournalEntryNotReversibleException ex, HttpServletRequest request) {
        String code = ex.getCurrentStatus() == JournalEntryStatus.REVERSED ? "JE_ALREADY_REVERSED" : "JE_NOT_POSTED";
        return build(HttpStatus.CONFLICT, code, ex.getMessage(), request);
    }

    /**
     * Operation dated into a CLOSED accounting period (AD-012); first used by
     * reversal-date validation (story A3), extended to all posting paths in
     * story B2.
     */
    @ExceptionHandler(AccountingPeriodClosedException.class)
    public ResponseEntity<ApiError> handlePeriodClosed(AccountingPeriodClosedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "PERIOD_CLOSED", ex.getMessage(), request);
    }

    /**
     * Operation dated strictly before the org-level hard-lock date (story
     * B2, issue #944). Unconditional — no override path.
     */
    @ExceptionHandler(AccountingPeriodHardLockedException.class)
    public ResponseEntity<ApiError> handlePeriodHardLocked(
            AccountingPeriodHardLockedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "PERIOD_HARD_LOCKED", ex.getMessage(), request);
    }

    /**
     * Hard-lock date update that would move the date backward (story B2,
     * issue #944): the hard lock is monotonic-forward-only.
     */
    @ExceptionHandler(HardLockDateRegressionException.class)
    public ResponseEntity<ApiError> handleHardLockDateRegression(
            HardLockDateRegressionException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "HARD_LOCK_DATE_REGRESSION", ex.getMessage(), request);
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

    /**
     * Publish-time split-group validation failure (story E1, issue #945):
     * factorPercent/splitGroup invariants violated in the rules definition.
     * Every violation is listed as a field error whose {@code field} locates
     * the offending group or line inside the rules definition.
     */
    @ExceptionHandler(UnbalancedRulesException.class)
    public ResponseEntity<ApiError> handleUnbalancedRules(UnbalancedRulesException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        List<ApiError.FieldError> fieldErrors = ex.getViolations().stream()
                .map(violation -> new ApiError.FieldError(violation.field(), violation.message()))
                .toList();
        return new ResponseEntity<>(
                ApiError.withFieldErrors(
                        "UNBALANCED_RULES",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors),
                headers,
                HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /**
     * Settlement reconciliation errors (story F1c, issue #963, PR #977 finding 5):
     * dedicated codes so ADR-0017 clients can branch on the failure instead of a
     * generic REQUEST_FAILED.
     */
    @ExceptionHandler(SettlementLineNotFoundException.class)
    public ResponseEntity<ApiError> handleSettlementLineNotFound(
            SettlementLineNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "SETTLEMENT_LINE_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(ReceivablePaymentNotFoundException.class)
    public ResponseEntity<ApiError> handleReceivablePaymentNotFound(
            ReceivablePaymentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "RECEIVABLE_PAYMENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(SettlementLineNotUnmatchedException.class)
    public ResponseEntity<ApiError> handleSettlementLineNotUnmatched(
            SettlementLineNotUnmatchedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "SETTLEMENT_LINE_NOT_UNMATCHED", ex.getMessage(), request);
    }

    /**
     * Manual match or write-off attempted before the settlement's batched JE
     * posted (story F1c, PR #977 finding 2). 409 while still RECEIVED.
     */
    @ExceptionHandler(SettlementNotPostedException.class)
    public ResponseEntity<ApiError> handleSettlementNotPosted(
            SettlementNotPostedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "SETTLEMENT_NOT_POSTED", ex.getMessage(), request);
    }

    @ExceptionHandler(SettlementWriteOffThresholdExceededException.class)
    public ResponseEntity<ApiError> handleWriteOffThresholdExceeded(
            SettlementWriteOffThresholdExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "WRITE_OFF_THRESHOLD_EXCEEDED", ex.getMessage(), request);
    }

    /**
     * Single-application reversal of a multi-application apply request (story C2,
     * PR #977 finding 3): must be reversed as a whole payment instead.
     */
    @ExceptionHandler(MultiApplicationReversalException.class)
    public ResponseEntity<ApiError> handleMultiApplicationReversal(
            MultiApplicationReversalException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "WHOLE_REQUEST_REVERSAL_REQUIRED", ex.getMessage(), request);
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
