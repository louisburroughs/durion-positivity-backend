package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.VendorBillStatus;
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
import com.positivity.shared.error.ApiError;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link AccountingExceptionHandler}.
 *
 * <p>{@code AccountingExceptionHandler} was already fully compliant with ADR-0017 §4 before this
 * test class was added: every handler resolves the correlation id via {@code
 * resolveCorrelationId} and sets it in both the {@link ApiError} body and the {@code
 * X-Correlation-Id} response header — either through the private {@code build} helper, or (for
 * the four handlers that need field errors or a caller-supplied status) by constructing the same
 * {@code HttpHeaders}/{@code X-Correlation-Id} pair directly. No production code changed for
 * issue #1729; this class only adds the header-contract guard so the class stays compliant.
 */
class AccountingExceptionHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String CORRELATION_ID = "test-correlation-id-0001";

    private static HttpServletRequest requestWithHeader(String value) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CORRELATION_ID_HEADER)).thenReturn(value);
        return request;
    }

    private static HttpServletRequest requestWithoutHeader() {
        return requestWithHeader(null);
    }

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link AccountingExceptionHandler}.
         * Uses a standalone handler instance so this factory method can stay static, as required
         * by {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            AccountingExceptionHandler handler = new AccountingExceptionHandler(TEST_CLOCK);

            return Stream.of(
                    Named.of("handleAuth", (HandlerInvocation) request -> handler.handleAuth(
                            new AuthenticationCredentialsNotFoundException("no credentials"), request)),
                    Named.of("handleAccessDenied", (HandlerInvocation)
                            request -> handler.handleAccessDenied(new AccessDeniedException("denied"), request)),
                    Named.of("handleVendorBillDuplicate", (HandlerInvocation)
                            request -> handler.handleVendorBillDuplicate(vendorBillDuplicate(), request)),
                    Named.of("handleInvalidDateRange", (HandlerInvocation) request ->
                            handler.handleInvalidDateRange(new InvalidDateRangeException("end before start"), request)),
                    Named.of("handleInvalidRequestParameter", (HandlerInvocation)
                            request -> handler.handleInvalidRequestParameter(
                                    new InvalidRequestParameterException("bad parameter"), request)),
                    Named.of("handleEventValidation", (HandlerInvocation) request ->
                            handler.handleEventValidation(new EventValidationException("missing field"), request)),
                    Named.of("handlePostingRulePublishValidation", (HandlerInvocation)
                            request -> handler.handlePostingRulePublishValidation(
                                    new PostingRulePublishValidationException("no DRAFT version"), request)),
                    Named.of("handleJournalEntryNotFound", (HandlerInvocation)
                            request -> handler.handleJournalEntryNotFound(
                                    new JournalEntryNotFoundException("not found"), request)),
                    Named.of("handleDefaultGLMappingNotFound", (HandlerInvocation)
                            request -> handler.handleDefaultGLMappingNotFound(
                                    new DefaultGLMappingNotFoundException("not found"), request)),
                    Named.of("handlePostingRuleSetNotFound", (HandlerInvocation)
                            request -> handler.handlePostingRuleSetNotFound(
                                    new PostingRuleSetNotFoundException("not found"), request)),
                    Named.of("handleGLAccountNotFound", (HandlerInvocation) request ->
                            handler.handleGLAccountNotFound(new GLAccountNotFoundException("not found"), request)),
                    Named.of("handleGLAccountNotActive", (HandlerInvocation) request ->
                            handler.handleGLAccountNotActive(new GLAccountNotActiveException("not active"), request)),
                    Named.of("handleGLMappingNotConfigured", (HandlerInvocation)
                            request -> handler.handleGLMappingNotConfigured(
                                    new GLMappingNotConfiguredException("not configured"), request)),
                    Named.of("handleAccountNotZeroBalance", (HandlerInvocation)
                            request -> handler.handleAccountNotZeroBalance(
                                    new AccountNotZeroBalanceException("non-zero balance"), request)),
                    Named.of("handleAccountNotInactive", (HandlerInvocation) request ->
                            handler.handleAccountNotInactive(new AccountNotInactiveException("not inactive"), request)),
                    Named.of("handleDuplicateEvent", (HandlerInvocation)
                            request -> handler.handleDuplicateEvent(new DuplicateEventException("duplicate"), request)),
                    Named.of("handleUnbalancedEntry", (HandlerInvocation) request ->
                            handler.handleUnbalancedEntry(new UnbalancedEntryException("unbalanced"), request)),
                    Named.of("handleValidation", (HandlerInvocation) request -> {
                        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
                        BindingResult bindingResult = mock(BindingResult.class);
                        when(bindingResult.getFieldErrors())
                                .thenReturn(List.of(new FieldError("object", "field", "required")));
                        when(ex.getBindingResult()).thenReturn(bindingResult);
                        return handler.handleValidation(ex, request);
                    }),
                    Named.of("handleIllegalState", (HandlerInvocation) request ->
                            handler.handleIllegalState(new IllegalStateException("Some other invalid state"), request)),
                    Named.of("handleEventNotRetryable", (HandlerInvocation) request ->
                            handler.handleEventNotRetryable(new EventNotRetryableException("not FAILED"), request)),
                    Named.of("handleDuplicateAccountCode", (HandlerInvocation)
                            request -> handler.handleDuplicateAccountCode(
                                    new DuplicateAccountCodeException("duplicate code"), request)),
                    Named.of("handleNotReversible", (HandlerInvocation) request -> handler.handleNotReversible(
                            new JournalEntryNotReversibleException(UUID.randomUUID(), JournalEntryStatus.REVERSED),
                            request)),
                    Named.of("handlePeriodClosed", (HandlerInvocation) request -> handler.handlePeriodClosed(
                            new AccountingPeriodClosedException("2024-01", "period closed"), request)),
                    Named.of("handlePeriodHardLocked", (HandlerInvocation) request -> handler.handlePeriodHardLocked(
                            new AccountingPeriodHardLockedException(LocalDate.of(2024, 1, 1), "hard locked"), request)),
                    Named.of("handleInvalidAccountingTimeZone", (HandlerInvocation)
                            request -> handler.handleInvalidAccountingTimeZone(
                                    new InvalidAccountingTimeZoneException("+05:00", "fixed offset"), request)),
                    Named.of("handleAccountingTimeZoneLocked", (HandlerInvocation)
                            request -> handler.handleAccountingTimeZoneLocked(
                                    new AccountingTimeZoneLockedException("UTC", "America/Chicago"), request)),
                    Named.of("handleAccountingTimeZoneUnset", (HandlerInvocation) request ->
                            handler.handleAccountingTimeZoneUnset(new AccountingTimeZoneUnsetException(), request)),
                    Named.of("handleHardLockDateRegression", (HandlerInvocation)
                            request -> handler.handleHardLockDateRegression(
                                    new HardLockDateRegressionException(
                                            LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 1)),
                                    request)),
                    Named.of("handleEntityNotFound", (HandlerInvocation)
                            request -> handler.handleEntityNotFound(new EntityNotFoundException("not found"), request)),
                    Named.of("handlePeriodNotFound", (HandlerInvocation) request -> handler.handlePeriodNotFound(
                            new AccountingPeriodNotFoundException("2024-01", "not found"), request)),
                    Named.of("handlePeriodStateConflict", (HandlerInvocation)
                            request -> handler.handlePeriodStateConflict(
                                    new AccountingPeriodStateException(
                                            "2024-01", AccountingPeriodStatus.CLOSED, "already closed"),
                                    request)),
                    Named.of("handleTaxSnapshotNotFound", (HandlerInvocation)
                            request -> handler.handleTaxSnapshotNotFound(
                                    new TaxSnapshotNotFoundException(UUID.randomUUID()), request)),
                    Named.of("handleTaxSnapshotPeriodNotClosed", (HandlerInvocation)
                            request -> handler.handleTaxSnapshotPeriodNotClosed(
                                    new TaxSnapshotPeriodNotClosedException("2024-01", "not closed"), request)),
                    Named.of("handleTaxSnapshotConflict", (HandlerInvocation)
                            request -> handler.handleTaxSnapshotConflict(
                                    new TaxSnapshotConflictException(UUID.randomUUID(), "already exists"), request)),
                    Named.of("handlePeriodCloseBlocked", (HandlerInvocation)
                            request -> handler.handlePeriodCloseBlocked(
                                    new PeriodCloseBlockedException("2024-01", List.of(UUID.randomUUID())), request)),
                    Named.of("handlePeriodBankReconciliationIncomplete", (HandlerInvocation) request ->
                            handler.handlePeriodBankReconciliationIncomplete(
                                    new PeriodBankReconciliationIncompleteException(
                                            "2024-01",
                                            List.of(new PeriodBankReconciliationIncompleteException.UnreconciledAccount(
                                                    UUID.randomUUID(), "1000", List.of("STATEMENT_COVERAGE"))),
                                            null),
                                    request)),
                    Named.of("handlePeriodCloseExceptionNotPermitted", (HandlerInvocation)
                            request -> handler.handlePeriodCloseExceptionNotPermitted(
                                    new PeriodCloseExceptionNotPermittedException("needs override"), request)),
                    Named.of("handleUnbalancedRules", (HandlerInvocation) request -> handler.handleUnbalancedRules(
                            new UnbalancedRulesException(
                                    List.of(new UnbalancedRulesException.RuleViolation("field", "message"))),
                            request)),
                    Named.of("handleSettlementLineNotFound", (HandlerInvocation)
                            request -> handler.handleSettlementLineNotFound(
                                    new SettlementLineNotFoundException("not found"), request)),
                    Named.of("handleReceivablePaymentNotFound", (HandlerInvocation)
                            request -> handler.handleReceivablePaymentNotFound(
                                    new ReceivablePaymentNotFoundException("not found"), request)),
                    Named.of("handleSettlementLineNotUnmatched", (HandlerInvocation)
                            request -> handler.handleSettlementLineNotUnmatched(
                                    new SettlementLineNotUnmatchedException("not unmatched"), request)),
                    Named.of("handleSettlementNotPosted", (HandlerInvocation) request ->
                            handler.handleSettlementNotPosted(new SettlementNotPostedException("not posted"), request)),
                    Named.of("handleWriteOffThresholdExceeded", (HandlerInvocation)
                            request -> handler.handleWriteOffThresholdExceeded(
                                    new SettlementWriteOffThresholdExceededException("exceeded"), request)),
                    Named.of("handleMultiApplicationReversal", (HandlerInvocation)
                            request -> handler.handleMultiApplicationReversal(
                                    new MultiApplicationReversalException("reverse whole payment"), request)),
                    Named.of("handleCurrencyNotSupported", (HandlerInvocation)
                            request -> handler.handleCurrencyNotSupported(
                                    new CurrencyNotSupportedException("payment EUR, invoice USD"), request)),
                    Named.of("handlePaymentNotFound", (HandlerInvocation) request ->
                            handler.handlePaymentNotFound(new PaymentNotFoundException("not found"), request)),
                    Named.of("handlePaymentNotAvailable", (HandlerInvocation)
                            request -> handler.handlePaymentNotAvailable(
                                    new PaymentNotAvailableException("fully applied"), request)),
                    Named.of("handlePaymentRemainderChanged", (HandlerInvocation)
                            request -> handler.handlePaymentRemainderChanged(
                                    new PaymentRemainderChangedException("remainder changed"), request)),
                    Named.of("handleCashCustomerCreditNotAllowed", (HandlerInvocation)
                            request -> handler.handleCashCustomerCreditNotAllowed(
                                    new CashCustomerCreditNotAllowedException("walk-in excess"), request)),
                    Named.of("handleAccountNotReconcilable", (HandlerInvocation)
                            request -> handler.handleAccountNotReconcilable(
                                    new AccountNotReconcilableException("not reconcilable"), request)),
                    Named.of("handleReconciliationNotFound", (HandlerInvocation)
                            request -> handler.handleReconciliationNotFound(
                                    new ReconciliationNotFoundException("not found"), request)),
                    Named.of("handleReconciliationAlreadyFinalized", (HandlerInvocation)
                            request -> handler.handleReconciliationAlreadyFinalized(
                                    new ReconciliationAlreadyFinalizedException("already finalized"), request)),
                    Named.of("handleMatchAmountMismatch", (HandlerInvocation)
                            request -> handler.handleMatchAmountMismatch(
                                    new MatchAmountMismatchException("amount mismatch"), request)),
                    Named.of("handleAdjustmentSignInvalid", (HandlerInvocation)
                            request -> handler.handleAdjustmentSignInvalid(
                                    new AdjustmentSignInvalidException(BankAdjustmentType.BANK_FEE), request)),
                    Named.of("handleReconciliationLineIneligible", (HandlerInvocation)
                            request -> handler.handleReconciliationLineIneligible(
                                    new ReconciliationLineIneligibleException("ineligible"), request)),
                    Named.of("handleReconciliationNotBalanced", (HandlerInvocation)
                            request -> handler.handleReconciliationNotBalanced(
                                    new ReconciliationNotBalancedException("not balanced", BigDecimal.TEN), request)),
                    Named.of("handleBankRec", (HandlerInvocation) request -> handler.handleBankRec(
                            BankRecException.field(
                                    BankRecErrorCode.STATEMENT_NOT_CONTIGUOUS,
                                    "gap",
                                    "openingBalance",
                                    "expected 1.00"),
                            request)),
                    Named.of("handleOptimisticLock", (HandlerInvocation) request -> handler.handleOptimisticLock(
                            new ObjectOptimisticLockingFailureException(Object.class, "id"), request)),
                    Named.of("handleResponseStatus", (HandlerInvocation) request -> handler.handleResponseStatus(
                            new ResponseStatusException(HttpStatus.BAD_GATEWAY, "bad gateway"), request)));
        }

        private static VendorBillDuplicateException vendorBillDuplicate() {
            VendorBill original = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"));
            original.setBillNumber("INV-00123");
            original.setBillDate(LocalDateTime.of(2026, 10, 1, 9, 30));
            original.setStatus(VendorBillStatus.APPROVED);
            return new VendorBillDuplicateException(original);
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithHeader(CORRELATION_ID));

            assertThat(response.getHeaders().getFirst(CORRELATION_ID_HEADER)).isEqualTo(CORRELATION_ID);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst(CORRELATION_ID_HEADER))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithoutHeader());

            String header = response.getHeaders().getFirst(CORRELATION_ID_HEADER);
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithHeader("   "));

            String header = response.getHeaders().getFirst(CORRELATION_ID_HEADER);
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on AccountingExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(AccountingExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to AccountingExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in AccountingExceptionHandlerTest "
                            + "— add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }

    @Nested
    @DisplayName("bank reconciliation refusals (SPEC-manual-bank-reconciliation §4.10, #2301)")
    class BankRecRefusals {

        private final AccountingExceptionHandler handler = new AccountingExceptionHandler(TEST_CLOCK);

        @Test
        @DisplayName(
                "PERIOD_BANK_RECONCILIATION_INCOMPLETE: 422, one unreconciledGlAccountIds entry per account (#2305)")
        void periodBankReconciliationIncomplete() {
            UUID account = UUID.randomUUID();
            ResponseEntity<ApiError> response = handler.handlePeriodBankReconciliationIncomplete(
                    new PeriodBankReconciliationIncompleteException(
                            "2026-08",
                            List.of(new PeriodBankReconciliationIncompleteException.UnreconciledAccount(
                                    account, "1000", List.of("RECONCILIATION_IN_FLIGHT", "RECONCILIATION_APPROVED"))),
                            "not permitted by policy REQUIRED"),
                    requestWithoutHeader());

            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("PERIOD_BANK_RECONCILIATION_INCOMPLETE");
            assertThat(response.getBody().fieldErrors())
                    .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    "unreconciledGlAccountIds",
                                    account + " 1000: RECONCILIATION_IN_FLIGHT, RECONCILIATION_APPROVED"),
                            org.assertj.core.groups.Tuple.tuple(
                                    "bankReconciliationException", "not permitted by policy REQUIRED"));
        }

        @Test
        @DisplayName("PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED: 403 (#2305)")
        void periodCloseExceptionNotPermitted() {
            ResponseEntity<ApiError> response = handler.handlePeriodCloseExceptionNotPermitted(
                    new PeriodCloseExceptionNotPermittedException("needs override"), requestWithoutHeader());

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED");
        }

        @Test
        @DisplayName("every code answers its ADR-0017 status with its own name")
        void everyCodeAnswersItsStatus() {
            for (BankRecErrorCode code : BankRecErrorCode.values()) {
                ResponseEntity<ApiError> response =
                        handler.handleBankRec(new BankRecException(code, "refused"), requestWithoutHeader());
                assertThat(response.getStatusCode().value()).as(code.name()).isEqualTo(code.httpStatus());
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().code()).isEqualTo(code.name());
            }
        }

        @Test
        @DisplayName("field errors travel in the envelope in order")
        void fieldErrorsTravelInTheEnvelope() {
            ResponseEntity<ApiError> response = handler.handleBankRec(
                    BankRecException.field(
                            BankRecErrorCode.STATEMENT_NOT_CONTIGUOUS, "gap", "openingBalance", "expected 12345.67"),
                    requestWithoutHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("openingBalance", "expected 12345.67"));
        }
    }

    @Nested
    @DisplayName("EVENT_NOT_RETRYABLE (#2411)")
    class EventRetry {

        private final AccountingExceptionHandler handler = new AccountingExceptionHandler(TEST_CLOCK);

        @Test
        @DisplayName("a retry of a non-FAILED event answers 409 EVENT_NOT_RETRYABLE")
        void eventNotRetryableIs409() {
            ResponseEntity<ApiError> response = handler.handleEventNotRetryable(
                    new EventNotRetryableException("not FAILED"), requestWithoutHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("EVENT_NOT_RETRYABLE");
            assertThat(response.getBody().status()).isEqualTo(409);
        }
    }

    @Nested
    @DisplayName("OPTIMISTIC_LOCK (SPEC-manual-bank-reconciliation §6.3, #2300)")
    class OptimisticLock {

        private final AccountingExceptionHandler handler = new AccountingExceptionHandler(TEST_CLOCK);

        @Test
        @DisplayName("a Spring optimistic-locking failure answers 409 OPTIMISTIC_LOCK")
        void springOptimisticLockingFailureIs409() {
            ResponseEntity<ApiError> response = handler.handleOptimisticLock(
                    new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID()),
                    requestWithoutHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("OPTIMISTIC_LOCK");
            assertThat(response.getBody().status()).isEqualTo(409);
        }

        @Test
        @DisplayName("a raw JPA OptimisticLockException answers 409 OPTIMISTIC_LOCK")
        void jpaOptimisticLockExceptionIs409() {
            ResponseEntity<ApiError> response =
                    handler.handleOptimisticLock(new OptimisticLockException("stale"), requestWithoutHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("OPTIMISTIC_LOCK");
        }
    }

    @Test
    @DisplayName("CurrencyNotSupportedException maps to 422 CURRENCY_NOT_SUPPORTED in the ApiError envelope (#2334)")
    void currencyNotSupportedIs422() {
        ResponseEntity<ApiError> response = new AccountingExceptionHandler(TEST_CLOCK)
                .handleCurrencyNotSupported(
                        new CurrencyNotSupportedException("payment EUR, invoice USD"), requestWithoutHeader());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(response.getBody().status()).isEqualTo(422);
    }

    @Test
    @DisplayName("Remainder-credit refusals map to PAYMENT_NOT_FOUND 404, PAYMENT_NOT_AVAILABLE 409 and "
            + "PAYMENT_REMAINDER_CHANGED 422 (#2524)")
    void remainderCreditCodes() {
        AccountingExceptionHandler handler = new AccountingExceptionHandler(TEST_CLOCK);

        ResponseEntity<ApiError> notFound =
                handler.handlePaymentNotFound(new PaymentNotFoundException("missing"), requestWithoutHeader());
        ResponseEntity<ApiError> notAvailable = handler.handlePaymentNotAvailable(
                new PaymentNotAvailableException("fully applied"), requestWithoutHeader());
        ResponseEntity<ApiError> changed = handler.handlePaymentRemainderChanged(
                new PaymentRemainderChangedException("remainder changed"), requestWithoutHeader());

        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody()).isNotNull();
        assertThat(notFound.getBody().code()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(notAvailable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notAvailable.getBody()).isNotNull();
        assertThat(notAvailable.getBody().code()).isEqualTo("PAYMENT_NOT_AVAILABLE");
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(changed.getBody()).isNotNull();
        assertThat(changed.getBody().code()).isEqualTo("PAYMENT_REMAINDER_CHANGED");
    }
}
