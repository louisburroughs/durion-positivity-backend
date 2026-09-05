package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.BankAdjustmentType;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountNotInactiveException;
import com.positivity.accounting.internal.exception.AccountNotReconcilableException;
import com.positivity.accounting.internal.exception.AccountNotZeroBalanceException;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.AccountingPeriodStateException;
import com.positivity.accounting.internal.exception.AdjustmentSignInvalidException;
import com.positivity.accounting.internal.exception.BankStatementParseException;
import com.positivity.accounting.internal.exception.DefaultGLMappingNotFoundException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;
import com.positivity.accounting.internal.exception.EventValidationException;
import com.positivity.accounting.internal.exception.GLAccountNotActiveException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.HardLockDateRegressionException;
import com.positivity.accounting.internal.exception.InvalidDateRangeException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.JournalEntryNotFoundException;
import com.positivity.accounting.internal.exception.JournalEntryNotReversibleException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import com.positivity.accounting.internal.exception.MultiApplicationReversalException;
import com.positivity.accounting.internal.exception.PeriodCloseBlockedException;
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
import com.positivity.shared.error.ApiError;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
                    Named.of("handleInvalidDateRange", (HandlerInvocation) request ->
                            handler.handleInvalidDateRange(new InvalidDateRangeException("end before start"), request)),
                    Named.of("handleInvalidRequestParameter", (HandlerInvocation)
                            request -> handler.handleInvalidRequestParameter(
                                    new InvalidRequestParameterException("bad parameter"), request)),
                    Named.of(
                            "handleBankStatementParse", (HandlerInvocation) request -> handler.handleBankStatementParse(
                                    new BankStatementParseException("malformed row"), request)),
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
                    Named.of("handleResponseStatus", (HandlerInvocation) request -> handler.handleResponseStatus(
                            new ResponseStatusException(HttpStatus.BAD_GATEWAY, "bad gateway"), request)));
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
}
