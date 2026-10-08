package com.positivity.accounting.internal.config;

import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountNotInactiveException;
import com.positivity.accounting.internal.exception.AccountNotReconcilableException;
import com.positivity.accounting.internal.exception.AccountNotZeroBalanceException;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.AccountingPeriodStateException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.AdjustmentSignInvalidException;
import com.positivity.accounting.internal.exception.CashCustomerCreditNotAllowedException;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.DefaultGLMappingNotFoundException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;
import com.positivity.accounting.internal.exception.EventNotRetryableException;
import com.positivity.accounting.internal.exception.EventValidationException;
import com.positivity.accounting.internal.exception.GLAccountNotActiveException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.HardLockDateRegressionException;
import com.positivity.accounting.internal.exception.InvalidAccountingTimeZoneException;
import com.positivity.accounting.internal.exception.InvalidDateRangeException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.JournalEntryNotFoundException;
import com.positivity.accounting.internal.exception.JournalEntryNotReversibleException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import com.positivity.accounting.internal.exception.MultiApplicationReversalException;
import com.positivity.accounting.internal.exception.PaymentNotAvailableException;
import com.positivity.accounting.internal.exception.PaymentNotFoundException;
import com.positivity.accounting.internal.exception.PaymentRemainderChangedException;
import com.positivity.accounting.internal.exception.PeriodBankReconciliationIncompleteException;
import com.positivity.accounting.internal.exception.PeriodCloseBlockedException;
import com.positivity.accounting.internal.exception.PeriodCloseExceptionNotPermittedException;
import com.positivity.accounting.internal.exception.PostingRulePublishValidationException;
import com.positivity.accounting.internal.exception.PostingRuleSetNotFoundException;
import com.positivity.accounting.internal.exception.ReceivablePaymentNotFoundException;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.exception.SettlementLineNotFoundException;
import com.positivity.accounting.internal.exception.SettlementLineNotUnmatchedException;
import com.positivity.accounting.internal.exception.SettlementNotPostedException;
import com.positivity.accounting.internal.exception.SettlementWriteOffThresholdExceededException;
import com.positivity.accounting.internal.exception.TaxSnapshotConflictException;
import com.positivity.accounting.internal.exception.TaxSnapshotNotFoundException;
import com.positivity.accounting.internal.exception.TaxSnapshotPeriodNotClosedException;
import com.positivity.accounting.internal.exception.UnbalancedRulesException;
import com.positivity.accounting.internal.exception.VendorBillDuplicateException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
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

    /*
     * Request-shape / field-validation failures (ADR-0017 §1, 400 VALIDATION_ERROR).
     * Deliberately several dedicated types instead of a blanket IllegalArgumentException
     * handler (issue #1694): each type is thrown only where a throw site was audited and
     * classified as genuine client input, so a Hibernate/JPA-thrown IllegalArgumentException
     * (bad JPQL, malformed stored UUID) no longer surfaces as a misleading 400 with internal
     * detail leaked into the body — it falls through to the pos-web-common catch-all instead.
     */
    @ExceptionHandler(InvalidDateRangeException.class)
    public ResponseEntity<ApiError> handleInvalidDateRange(InvalidDateRangeException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidRequestParameterException.class)
    public ResponseEntity<ApiError> handleInvalidRequestParameter(
            InvalidRequestParameterException ex, HttpServletRequest request) {
        if (ex.getField() == null) {
            return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
        }
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.withFieldErrors(
                        "VALIDATION_ERROR",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        List.of(new ApiError.FieldError(ex.getField(), ex.getMessage()))),
                headers,
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(EventValidationException.class)
    public ResponseEntity<ApiError> handleEventValidation(EventValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(PostingRulePublishValidationException.class)
    public ResponseEntity<ApiError> handlePostingRulePublishValidation(
            PostingRulePublishValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    /*
     * Not-found lookups (ADR-0017 §1, 404), each with its own code so clients can branch.
     *
     * JournalEntryNotFoundException, DefaultGLMappingNotFoundException and
     * PostingRuleSetNotFoundException used to answer 400 here, "deliberately not 404" per
     * @Operation prose that commit 52ed7b13 (issue #1694) added in the same change that flipped
     * their @ApiResponse annotations from a pre-existing, unremarked 404 — with no ADR
     * authorizing an override of ADR-0017 §1's default matrix (checked docs/adr/ and
     * domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md, which documents
     * PERIOD_NOT_FOUND as 404, the ADR-conformant default). All three name a genuinely missing
     * addressed resource — a journal entry, GL mapping or posting rule set by id — so they
     * converge on 404 here (review finding, same issue #1694). PostingRuleServiceImpl's
     * publishRuleSet/archiveRuleSet/listVersionsAsResponse gained their own existence check
     * (throwing this exception) alongside this change: they previously never reached it for a
     * genuinely missing rule set, masking the miss as a misleading 400 "no DRAFT/PUBLISHED
     * version" or a silent 200 empty list.
     */
    @ExceptionHandler(JournalEntryNotFoundException.class)
    public ResponseEntity<ApiError> handleJournalEntryNotFound(
            JournalEntryNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "JOURNAL_ENTRY_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(DefaultGLMappingNotFoundException.class)
    public ResponseEntity<ApiError> handleDefaultGLMappingNotFound(
            DefaultGLMappingNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "DEFAULT_GL_MAPPING_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(PostingRuleSetNotFoundException.class)
    public ResponseEntity<ApiError> handlePostingRuleSetNotFound(
            PostingRuleSetNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "POSTING_RULE_SET_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(GLAccountNotFoundException.class)
    public ResponseEntity<ApiError> handleGLAccountNotFound(GLAccountNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "GL_ACCOUNT_NOT_FOUND", ex.getMessage(), request);
    }

    /*
     * Domain-policy violations on an otherwise valid payload (ADR-0017 §2, 422).
     */
    @ExceptionHandler(GLAccountNotActiveException.class)
    public ResponseEntity<ApiError> handleGLAccountNotActive(
            GLAccountNotActiveException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "GL_ACCOUNT_NOT_ACTIVE", ex.getMessage(), request);
    }

    /**
     * A posting with no active mapping for one of its keys (#2601): 422, guided when the posting named the mapping
     * ({@code referenceId} {@code CATEGORY/KEY} and a {@code nextAction}), as a vendor-bill approval does.
     */
    @ExceptionHandler(GLMappingNotConfiguredException.class)
    public ResponseEntity<ApiError> handleGLMappingNotConfigured(
            GLMappingNotConfiguredException ex, HttpServletRequest request) {
        if (ex.getNextAction() == null) {
            return build(HttpStatus.UNPROCESSABLE_CONTENT, "GL_MAPPING_NOT_CONFIGURED", ex.getMessage(), request);
        }
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.guided(
                        "GL_MAPPING_NOT_CONFIGURED",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        ex.getReferenceId(),
                        ex.getNextAction(),
                        null),
                headers,
                HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /**
     * Deactivating a GL account with a non-zero balance (story: chart-of-accounts lifecycle):
     * current account state blocks the operation. 409 per ADR-0017 §2 (previously fell
     * through unmapped to a generic 500 — issue #1694).
     */
    @ExceptionHandler(AccountNotZeroBalanceException.class)
    public ResponseEntity<ApiError> handleAccountNotZeroBalance(
            AccountNotZeroBalanceException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "ACCOUNT_NOT_ZERO_BALANCE", ex.getMessage(), request);
    }

    /**
     * Archiving a GL account that is not INACTIVE: invalid lifecycle transition. 409 per
     * ADR-0017 §2 (previously fell through unmapped to a generic 500 — issue #1694).
     */
    @ExceptionHandler(AccountNotInactiveException.class)
    public ResponseEntity<ApiError> handleAccountNotInactive(
            AccountNotInactiveException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "ACCOUNT_NOT_INACTIVE", ex.getMessage(), request);
    }

    /**
     * Vendor-bill command refusals (#2509): the exception carries its code and status (404
     * {@code VENDOR_BILL_NOT_FOUND}, 409 {@code AP_BILL_NOT_APPROVABLE}, 422 {@code AP_BILL_UNCLASSIFIED}, ...).
     */
    @ExceptionHandler(VendorBillException.class)
    public ResponseEntity<ApiError> handleVendorBill(VendorBillException ex, HttpServletRequest request) {
        if (ex.getFieldErrors().isEmpty() && ex.getNextAction() == null) {
            return build(ex.getCode().status(), ex.getCode().name(), ex.getMessage(), request);
        }
        // CAP:550 S13 (#2510): fieldErrors naming the fields or bills (VALIDATION_ERROR of the AP approval policy,
        // AP_PAYMENT_SELF_APPROVED_BILL) and a nextAction (AP_APPROVAL_LIMIT_EXCEEDED names the permission needed).
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        List<ApiError.FieldError> fieldErrors = ex.getFieldErrors().isEmpty()
                ? null
                : ex.getFieldErrors().stream()
                        .map(error -> new ApiError.FieldError(error.field(), error.message()))
                        .toList();
        return new ResponseEntity<>(
                new ApiError(
                        ex.getCode().name(),
                        ex.getMessage(),
                        ex.getCode().status().value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors,
                        null,
                        ex.getNextAction(),
                        null),
                headers,
                ex.getCode().status());
    }

    /**
     * Register float and petty-expense category refusals (#2511): the exception carries its code and
     * status (409 {@code FLOAT_ALREADY_ESTABLISHED}, 422 {@code FLOAT_AMOUNT_UNCHANGED}, ...).
     */
    @ExceptionHandler(CashSetupException.class)
    public ResponseEntity<ApiError> handleCashSetup(CashSetupException ex, HttpServletRequest request) {
        if (ex.getReferenceId() == null) {
            return build(ex.getCode().status(), ex.getCode().name(), ex.getMessage(), request);
        }
        // A refusal naming the record in the way, e.g. the open session of FLOAT_REGISTER_SESSION_OPEN (#2571).
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.guided(
                        ex.getCode().name(),
                        ex.getMessage(),
                        ex.getCode().status().value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        ex.getReferenceId(),
                        ex.getNextAction(),
                        null),
                headers,
                ex.getCode().status());
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

    @ExceptionHandler(EventNotRetryableException.class)
    public ResponseEntity<ApiError> handleEventNotRetryable(EventNotRetryableException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "EVENT_NOT_RETRYABLE", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateAccountCodeException.class)
    public ResponseEntity<ApiError> handleDuplicateAccountCode(
            DuplicateAccountCodeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_ACCOUNT_CODE", ex.getMessage(), request);
    }

    /**
     * A vendor bill refused by the duplicate rule (#2501; ADR-0070 Decision 4): a live bill already
     * holds the same vendor, normalised bill number and bill date. 409 {@code AP_BILL_DUPLICATE}. The
     * message names the original by number, vendor and date and carries no id (ADR-0064); the
     * original's {@code vendorBillId} is the {@code referenceId}, the key for the client's link to
     * the existing bill.
     */
    @ExceptionHandler(VendorBillDuplicateException.class)
    public ResponseEntity<ApiError> handleVendorBillDuplicate(
            VendorBillDuplicateException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.guided(
                        "AP_BILL_DUPLICATE",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        ex.getOriginalBillId().toString(),
                        "Open the existing bill.",
                        null),
                headers,
                HttpStatus.CONFLICT);
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

    /** The requested accounting time zone is not an IANA region id (#2558). */
    @ExceptionHandler(InvalidAccountingTimeZoneException.class)
    public ResponseEntity<ApiError> handleInvalidAccountingTimeZone(
            InvalidAccountingTimeZoneException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_ACCOUNTING_TIME_ZONE", ex.getMessage(), request);
    }

    /** The accounting time zone is fixed once a period was closed or a hard-lock date set (#2558). */
    @ExceptionHandler(AccountingTimeZoneLockedException.class)
    public ResponseEntity<ApiError> handleAccountingTimeZoneLocked(
            AccountingTimeZoneLockedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "ACCOUNTING_TIME_ZONE_LOCKED", ex.getMessage(), request);
    }

    /** No accounting time zone is set, so nothing can be dated or posted (#2558). */
    @ExceptionHandler(AccountingTimeZoneUnsetException.class)
    public ResponseEntity<ApiError> handleAccountingTimeZoneUnset(
            AccountingTimeZoneUnsetException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNTING_TIME_ZONE_UNSET", ex.getMessage(), request);
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

    /**
     * Entity lookups that miss entirely (e.g. an invoice unknown to the {@code ext_invoice}
     * replica, issue #1634). The pos-web-common {@code GlobalApiExceptionHandler} has no mapping
     * for this exception (it would collapse to 500), so the module advice supplies the ADR-0017
     * 404 envelope.
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleEntityNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
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

    @ExceptionHandler(TaxSnapshotNotFoundException.class)
    public ResponseEntity<ApiError> handleTaxSnapshotNotFound(
            TaxSnapshotNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "TAX_SNAPSHOT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(TaxSnapshotPeriodNotClosedException.class)
    public ResponseEntity<ApiError> handleTaxSnapshotPeriodNotClosed(
            TaxSnapshotPeriodNotClosedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "TAX_SNAPSHOT_PERIOD_NOT_CLOSED", ex.getMessage(), request);
    }

    @ExceptionHandler(TaxSnapshotConflictException.class)
    public ResponseEntity<ApiError> handleTaxSnapshotConflict(
            TaxSnapshotConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "TAX_SNAPSHOT_ALREADY_EXISTS", ex.getMessage(), request);
    }

    /**
     * A close refused by the bank reconciliation close policy (SPEC-manual-bank-reconciliation §5.2, §5.9; story
     * S6, #2305): the {@code PERIOD_HAS_DRAFT_ENTRIES} shape — one {@code fieldErrors[unreconciledGlAccountIds]}
     * entry per blocked account whose message is {@code "<glAccountId> <accountCode>: <check codes>"} — plus
     * {@code fieldErrors[bankReconciliationException]} when the policy refused an exception.
     */
    @ExceptionHandler(PeriodBankReconciliationIncompleteException.class)
    public ResponseEntity<ApiError> handlePeriodBankReconciliationIncomplete(
            PeriodBankReconciliationIncompleteException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        List<ApiError.FieldError> fieldErrors = new ArrayList<>();
        for (PeriodBankReconciliationIncompleteException.UnreconciledAccount account : ex.getUnreconciledAccounts()) {
            fieldErrors.add(new ApiError.FieldError(
                    "unreconciledGlAccountIds",
                    account.glAccountId() + " " + account.accountCode() + ": "
                            + String.join(", ", account.checkCodes())));
        }
        if (ex.getRefusedExceptionReason() != null) {
            fieldErrors.add(new ApiError.FieldError("bankReconciliationException", ex.getRefusedExceptionReason()));
        }
        return new ResponseEntity<>(
                ApiError.withFieldErrors(
                        "PERIOD_BANK_RECONCILIATION_INCOMPLETE",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors),
                headers,
                HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @ExceptionHandler(PeriodCloseExceptionNotPermittedException.class)
    public ResponseEntity<ApiError> handlePeriodCloseExceptionNotPermitted(
            PeriodCloseExceptionNotPermittedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED", ex.getMessage(), request);
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

    /**
     * A payment in a currency the ledger does not book, applied to invoices (ADR-0067 PC-9 (a),
     * issues #2310 and #2334): refused before any amount moves. 422, not 409: the refusal is about
     * the referenced payment's state (ADR-0017 §2).
     */
    @ExceptionHandler(CurrencyNotSupportedException.class)
    public ResponseEntity<ApiError> handleCurrencyNotSupported(
            CurrencyNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "CURRENCY_NOT_SUPPORTED", ex.getMessage(), request);
    }

    /**
     * Crediting a payment's remainder (CAP:550 S35, #2524): the payment named by the command does
     * not exist.
     */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiError> handlePaymentNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage(), request);
    }

    /**
     * Crediting a payment's remainder (CAP:550 S35, #2524): the payment is no longer {@code
     * AVAILABLE}. 409: the conflict is with the payment's current state.
     */
    @ExceptionHandler(PaymentNotAvailableException.class)
    public ResponseEntity<ApiError> handlePaymentNotAvailable(
            PaymentNotAvailableException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "PAYMENT_NOT_AVAILABLE", ex.getMessage(), request);
    }

    /**
     * Crediting a payment's remainder (CAP:550 S35, #2524): the expected amount no longer matches
     * the payment's unapplied amount; nothing was written and the client re-reads. 422 (ADR-0017
     * §2).
     */
    @ExceptionHandler(PaymentRemainderChangedException.class)
    public ResponseEntity<ApiError> handlePaymentRemainderChanged(
            PaymentRemainderChangedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_REMAINDER_CHANGED", ex.getMessage(), request);
    }

    /**
     * A person's command would keep a CASH walk-in payment's money as a customer credit (CAP:550 S11,
     * #2508; §4.4 item 4): 422, the refusal is about the payment's customer. Nothing was written.
     */
    @ExceptionHandler(CashCustomerCreditNotAllowedException.class)
    public ResponseEntity<ApiError> handleCashCustomerCreditNotAllowed(
            CashCustomerCreditNotAllowedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "CASH_CUSTOMER_CREDIT_NOT_ALLOWED", ex.getMessage(), request);
    }

    /**
     * Bank reconciliation errors (story F2, issue #965): dedicated ADR-0017 codes.
     */
    @ExceptionHandler(AccountNotReconcilableException.class)
    public ResponseEntity<ApiError> handleAccountNotReconcilable(
            AccountNotReconcilableException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNT_NOT_RECONCILABLE", ex.getMessage(), request);
    }

    @ExceptionHandler(ReconciliationNotFoundException.class)
    public ResponseEntity<ApiError> handleReconciliationNotFound(
            ReconciliationNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "RECONCILIATION_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(ReconciliationAlreadyFinalizedException.class)
    public ResponseEntity<ApiError> handleReconciliationAlreadyFinalized(
            ReconciliationAlreadyFinalizedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "RECONCILIATION_ALREADY_FINALIZED", ex.getMessage(), request);
    }

    @ExceptionHandler(MatchAmountMismatchException.class)
    public ResponseEntity<ApiError> handleMatchAmountMismatch(
            MatchAmountMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "MATCH_AMOUNT_MISMATCH", ex.getMessage(), request);
    }

    @ExceptionHandler(AdjustmentSignInvalidException.class)
    public ResponseEntity<ApiError> handleAdjustmentSignInvalid(
            AdjustmentSignInvalidException ex, HttpServletRequest request) {
        return build(
                HttpStatus.UNPROCESSABLE_CONTENT, "RECONCILIATION_ADJUSTMENT_SIGN_INVALID", ex.getMessage(), request);
    }

    /**
     * A refusal of the bank reconciliation core (SPEC-manual-bank-reconciliation §4.10; story S2,
     * #2301): the exception carries its code and ADR-0017 status, and any field errors — for example
     * {@code fieldErrors[openingBalance] = "expected 12345.67"} on {@code STATEMENT_NOT_CONTIGUOUS}.
     */
    @ExceptionHandler(BankRecException.class)
    public ResponseEntity<ApiError> handleBankRec(BankRecException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.code().httpStatus());
        if (ex.fieldErrors().isEmpty()) {
            return build(status, ex.code().name(), ex.getMessage(), request);
        }
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        List<ApiError.FieldError> fieldErrors = ex.fieldErrors().entrySet().stream()
                .map(entry -> new ApiError.FieldError(entry.getKey(), entry.getValue()))
                .toList();
        return new ResponseEntity<>(
                ApiError.withFieldErrors(
                        ex.code().name(),
                        ex.getMessage(),
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors),
                headers,
                status);
    }

    @ExceptionHandler(ReconciliationLineIneligibleException.class)
    public ResponseEntity<ApiError> handleReconciliationLineIneligible(
            ReconciliationLineIneligibleException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "RECONCILIATION_LINE_INELIGIBLE", ex.getMessage(), request);
    }

    /**
     * Finalize attempted while the reconciliation does not balance (story F2). 422
     * with the outstanding {@code difference} carried as a field error so ADR-0017
     * clients can display it without re-deriving it.
     */
    @ExceptionHandler(ReconciliationNotBalancedException.class)
    public ResponseEntity<ApiError> handleReconciliationNotBalanced(
            ReconciliationNotBalancedException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        List<ApiError.FieldError> fieldErrors = List.of(new ApiError.FieldError(
                "difference", ex.getDifference() != null ? ex.getDifference().toPlainString() : null));
        return new ResponseEntity<>(
                ApiError.withFieldErrors(
                        "RECONCILIATION_NOT_BALANCED",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors),
                headers,
                HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /**
     * A stale {@code @Version} on save (SPEC-manual-bank-reconciliation §6.3, story S1 #2300; ADR-0017 §2
     * puts a version conflict in the 409 class). Before this mapping a version conflict in this module fell
     * through to the pos-web-common catch-all as a 500. The message names no entity or version: the
     * caller's remedy is the same either way — reload and retry.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiError> handleOptimisticLock(Exception ex, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                "OPTIMISTIC_LOCK",
                "The record was changed by another request; reload it and retry",
                request);
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
