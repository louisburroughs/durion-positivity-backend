package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.RemainderCreditRequest;
import com.positivity.accounting.internal.dto.RemainderCreditResponse;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link RetryingPaymentApplicationService} (Story C4, issue
 * #936): retry-once-on-optimistic-conflict semantics around the transactional
 * {@link PaymentApplicationServiceImpl} boundary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetryingPaymentApplicationService Unit Tests")
class RetryingPaymentApplicationServiceTest {

    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-00000000c401");

    @Mock
    private PaymentApplicationServiceImpl delegate;

    @InjectMocks
    private RetryingPaymentApplicationService retryingService;

    private PaymentApplicationRequest request;
    private PaymentApplicationResponse response;

    @BeforeEach
    void setUp() {
        PaymentApplicationRequest.InvoiceApplication invoiceApplication =
                new PaymentApplicationRequest.InvoiceApplication();
        invoiceApplication.setInvoiceId(UUID.fromString("00000000-0000-0000-0000-00000000c402"));
        invoiceApplication.setAmountToApply(new BigDecimal("100.00"));

        request = new PaymentApplicationRequest();
        request.setApplicationRequestId("c4-request-1");
        request.setApplications(List.of(invoiceApplication));

        response = PaymentApplicationResponse.builder()
                .paymentId(PAYMENT_ID)
                .appliedAmount(new BigDecimal("100.00"))
                .remainingAmount(new BigDecimal("900.00"))
                .applicationRequestId("c4-request-1")
                .build();
    }

    private static ObjectOptimisticLockingFailureException conflict() {
        return new ObjectOptimisticLockingFailureException("ReceivablePayment", PAYMENT_ID);
    }

    @Test
    @DisplayName("C4-U1: Single-writer path delegates once without retry")
    void applyPayment_noConflict_delegatesOnce() {
        when(delegate.applyPaymentToInvoices(PAYMENT_ID, request)).thenReturn(response);

        PaymentApplicationResponse result = retryingService.applyPaymentToInvoices(PAYMENT_ID, request);

        assertThat(result).isSameAs(response);
        verify(delegate).applyPaymentToInvoices(PAYMENT_ID, request);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    @DisplayName("C4-U2: First optimistic conflict retries exactly once and returns second result")
    void applyPayment_firstConflict_retriesOnce() {
        when(delegate.applyPaymentToInvoices(PAYMENT_ID, request))
                .thenThrow(conflict())
                .thenReturn(response);

        PaymentApplicationResponse result = retryingService.applyPaymentToInvoices(PAYMENT_ID, request);

        assertThat(result).isSameAs(response);
        verify(delegate, org.mockito.Mockito.times(2)).applyPaymentToInvoices(PAYMENT_ID, request);
    }

    @Test
    @DisplayName("C4-U3: Conflict on both attempts surfaces as 409 CONFLICT")
    void applyPayment_bothAttemptsConflict_mapsTo409() {
        when(delegate.applyPaymentToInvoices(PAYMENT_ID, request))
                .thenThrow(conflict())
                .thenThrow(conflict());

        assertThatThrownBy(() -> retryingService.applyPaymentToInvoices(PAYMENT_ID, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(RetryingPaymentApplicationService.isOptimisticLockConflict(ex.getCause()))
                            .isTrue();
                });
        verify(delegate, org.mockito.Mockito.times(2)).applyPaymentToInvoices(PAYMENT_ID, request);
    }

    @Test
    @DisplayName("C4-U4: Conflict wrapped in a commit exception cause chain is detected and retried")
    void applyPayment_wrappedConflict_isRetried() {
        TransactionSystemException wrapped = new TransactionSystemException(
                "Could not commit JPA transaction",
                new RollbackException("commit failed", new OptimisticLockException("stale ReceivablePayment")));
        when(delegate.applyPaymentToInvoices(PAYMENT_ID, request))
                .thenThrow(wrapped)
                .thenReturn(response);

        PaymentApplicationResponse result = retryingService.applyPaymentToInvoices(PAYMENT_ID, request);

        assertThat(result).isSameAs(response);
        verify(delegate, org.mockito.Mockito.times(2)).applyPaymentToInvoices(PAYMENT_ID, request);
    }

    @Test
    @DisplayName("C4-U5: Non-conflict failures propagate unchanged without retry")
    void applyPayment_businessFailure_notRetried() {
        ResponseStatusException insufficientFunds =
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient funds");
        when(delegate.applyPaymentToInvoices(PAYMENT_ID, request)).thenThrow(insufficientFunds);

        assertThatThrownBy(() -> retryingService.applyPaymentToInvoices(PAYMENT_ID, request))
                .isSameAs(insufficientFunds);
        verify(delegate).applyPaymentToInvoices(PAYMENT_ID, request);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    @DisplayName("C4-U6: AD-010 — replayed request that lost a race gets the recorded idempotent response on retry")
    void applyPayment_idempotentReplayAfterLostRace() {
        // First attempt: replayed request races another writer and conflicts at
        // commit. Retry re-runs the idempotency check against fresh state and the
        // delegate returns the recorded outcome instead of double-applying.
        PaymentApplicationResponse recordedOutcome = PaymentApplicationResponse.builder()
                .paymentId(PAYMENT_ID)
                .appliedAmount(new BigDecimal("100.00"))
                .remainingAmount(new BigDecimal("900.00"))
                .applicationRequestId("c4-request-1")
                .build();
        when(delegate.applyPaymentToInvoices(PAYMENT_ID, request))
                .thenThrow(conflict())
                .thenReturn(recordedOutcome);

        PaymentApplicationResponse result = retryingService.applyPaymentToInvoices(PAYMENT_ID, request);

        assertThat(result).isSameAs(recordedOutcome);
        verify(delegate, org.mockito.Mockito.times(2)).applyPaymentToInvoices(PAYMENT_ID, request);
    }

    @Test
    @DisplayName("C4-U7: Non-apply operations are plain pass-through delegation")
    void otherOperations_delegateDirectly() {
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-00000000c403");
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-00000000c404");
        UUID sourceEventId = UUID.fromString("00000000-0000-0000-0000-00000000c405");
        Instant clearedAt = Instant.parse("2026-01-01T00:00:00Z");

        retryingService.voidPayment(PAYMENT_ID);
        retryingService.reversePayment(PAYMENT_ID, "Customer requested reversal");
        retryingService.reversePaymentApplication(applicationId, "Customer disputed the charge");
        retryingService.handlePaymentCleared(
                PAYMENT_ID, customerId, "USD", new BigDecimal("1000.00"), clearedAt, sourceEventId);

        verify(delegate).voidPayment(PAYMENT_ID);
        verify(delegate).reversePayment(PAYMENT_ID, "Customer requested reversal");
        verify(delegate).reversePaymentApplication(applicationId, "Customer disputed the charge");
        verify(delegate)
                .handlePaymentCleared(
                        PAYMENT_ID, customerId, "USD", new BigDecimal("1000.00"), clearedAt, sourceEventId);
        verifyNoMoreInteractions(delegate);
    }

    // ===== Remainder credit (CAP:550 S35, #2524): apply's one retry =====

    private static RemainderCreditRequest remainderRequest() {
        return RemainderCreditRequest.builder()
                .requestId("remainder-1")
                .expectedAmount(new BigDecimal("12.50"))
                .build();
    }

    @Test
    @DisplayName("#2524: creditPaymentRemainder retries once on an optimistic-lock conflict and returns the retry's"
            + " result")
    void creditPaymentRemainder_firstConflict_retriesOnce() {
        RemainderCreditRequest request = remainderRequest();
        RemainderCreditResponse credited = RemainderCreditResponse.builder()
                .paymentId(PAYMENT_ID)
                .requestId("remainder-1")
                .creditId(UUID.fromString("00000000-0000-0000-0000-00000000c4c1"))
                .amount(new BigDecimal("12.50"))
                .remainingAmount(BigDecimal.ZERO)
                .createdAt(Instant.parse("2026-10-06T00:00:00Z"))
                .build();
        when(delegate.creditPaymentRemainder(PAYMENT_ID, request))
                .thenThrow(conflict())
                .thenReturn(credited);

        RemainderCreditResponse result = retryingService.creditPaymentRemainder(PAYMENT_ID, request);

        assertThat(result).isSameAs(credited);
        verify(delegate, org.mockito.Mockito.times(2)).creditPaymentRemainder(PAYMENT_ID, request);
    }

    @Test
    @DisplayName(
            "#2524: a second conflict on creditPaymentRemainder surfaces as OPTIMISTIC_LOCK (409), not a third try")
    void creditPaymentRemainder_bothAttemptsConflict_isOptimisticLock() {
        RemainderCreditRequest request = remainderRequest();
        when(delegate.creditPaymentRemainder(PAYMENT_ID, request))
                .thenThrow(conflict())
                .thenThrow(conflict());

        assertThatThrownBy(() -> retryingService.creditPaymentRemainder(PAYMENT_ID, request))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(delegate, org.mockito.Mockito.times(2)).creditPaymentRemainder(PAYMENT_ID, request);
    }

    @Test
    @DisplayName("#2524: a concurrent request that inserted the same requestId first (uq_customer_credit_request_id)"
            + " is retried once, so the retry replays the winner's credit")
    void creditPaymentRemainder_requestIdRace_retriesAndReplays() {
        RemainderCreditRequest request = remainderRequest();
        RemainderCreditResponse winner = RemainderCreditResponse.builder()
                .paymentId(PAYMENT_ID)
                .requestId("remainder-1")
                .creditId(UUID.fromString("00000000-0000-0000-0000-00000000c4c2"))
                .amount(new BigDecimal("12.50"))
                .remainingAmount(BigDecimal.ZERO)
                .build();
        when(delegate.creditPaymentRemainder(PAYMENT_ID, request))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement",
                        new RuntimeException("ERROR: duplicate key value violates unique constraint"
                                + " \"uq_customer_credit_request_id\"")))
                .thenReturn(winner);

        assertThat(retryingService.creditPaymentRemainder(PAYMENT_ID, request)).isSameAs(winner);
        verify(delegate, org.mockito.Mockito.times(2)).creditPaymentRemainder(PAYMENT_ID, request);
    }

    @Test
    @DisplayName("#2524: any other integrity violation on creditPaymentRemainder is not retried")
    void creditPaymentRemainder_otherIntegrityViolation_notRetried() {
        RemainderCreditRequest request = remainderRequest();
        when(delegate.creditPaymentRemainder(PAYMENT_ID, request))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "chk_customer_credit_consumed_within_amount"));

        assertThatThrownBy(() -> retryingService.creditPaymentRemainder(PAYMENT_ID, request))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verify(delegate).creditPaymentRemainder(PAYMENT_ID, request);
    }

    @Test
    @DisplayName("#2524: a business refusal on creditPaymentRemainder is not retried")
    void creditPaymentRemainder_businessFailure_notRetried() {
        RemainderCreditRequest request = remainderRequest();
        when(delegate.creditPaymentRemainder(PAYMENT_ID, request))
                .thenThrow(new com.positivity.accounting.internal.exception.PaymentRemainderChangedException("stale"));

        assertThatThrownBy(() -> retryingService.creditPaymentRemainder(PAYMENT_ID, request))
                .isInstanceOf(com.positivity.accounting.internal.exception.PaymentRemainderChangedException.class);
        verify(delegate).creditPaymentRemainder(PAYMENT_ID, request);
        verifyNoMoreInteractions(delegate);
    }
}
