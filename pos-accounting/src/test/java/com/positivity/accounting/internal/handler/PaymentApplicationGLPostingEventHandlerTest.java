package com.positivity.accounting.internal.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.service.GLMappingResolver;
import com.positivity.accounting.service.GLPostingService;
import com.positivity.accounting.service.IdempotencyService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PaymentApplicationGLPostingEventHandler} (story C1,
 * issue #954): AR cash receipt GL posting — Dr Undeposited Funds, Cr AR.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentApplicationGLPostingEventHandler")
class PaymentApplicationGLPostingEventHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC);
    private static final String APPLICATION_REQUEST_ID = "0195f1a2-0000-7000-8000-000000000042";
    private static final String IDEMPOTENCY_KEY = "PAYMENT_APPLICATION_GL_POSTING:" + APPLICATION_REQUEST_ID;

    @Spy
    private Clock clock = TEST_CLOCK;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private GLPostingService glPostingService;

    @InjectMocks
    private PaymentApplicationGLPostingEventHandler handler;

    private PaymentApplicationGLPostingEvent testEvent;
    private UUID undepositedFundsAccountId;
    private UUID arAccountId;
    private UUID journalEntryId;
    private JournalEntry postedEntry;

    @BeforeEach
    void setUp() {
        undepositedFundsAccountId = UUID.fromString("00000000-0000-0000-0000-000000001090");
        arAccountId = UUID.fromString("00000000-0000-0000-0000-000000001200");
        journalEntryId = UUID.fromString("00000000-0000-0000-0000-00000000e001");

        testEvent = PaymentApplicationGLPostingEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-00000000a001"))
                .applicationRequestId(APPLICATION_REQUEST_ID)
                .paymentId(UUID.fromString("00000000-0000-0000-0000-00000000b001"))
                .customerId(UUID.fromString("00000000-0000-0000-0000-00000000b002"))
                .currency("USD")
                .appliedAmount(new BigDecimal("500.00"))
                .applicationTimestamp(Instant.parse("2026-03-15T09:59:00Z"))
                .build();

        postedEntry = new JournalEntry();
        postedEntry.setJournalEntryId(journalEntryId);
    }

    private void stubAccountResolution() {
        when(glMappingResolver.resolveGLAccount(
                        eq("PAYMENT_APPLICATION"), eq("UNDEPOSITED_FUNDS"), any(LocalDateTime.class)))
                .thenReturn(undepositedFundsAccountId);
        when(glMappingResolver.resolveGLAccount(
                        eq("PAYMENT_APPLICATION"), eq("ACCOUNTS_RECEIVABLE"), any(LocalDateTime.class)))
                .thenReturn(arAccountId);
    }

    @Test
    @DisplayName("happy path posts Dr Undeposited Funds / Cr AR with sourceEventId = application request id")
    void happyPath_postsBalancedEntryWithResolvedAccounts() {
        stubAccountResolution();
        when(glPostingService.postPaymentApplication(
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any(BigDecimal.class),
                        any(LocalDateTime.class),
                        any(String.class)))
                .thenReturn(postedEntry);

        handler.onPaymentApplicationGLPosting(testEvent);

        ArgumentCaptor<UUID> sourceEventIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> debitAccountCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> creditAccountCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> descriptionCaptor = ArgumentCaptor.forClass(String.class);

        verify(glPostingService)
                .postPaymentApplication(
                        sourceEventIdCaptor.capture(),
                        debitAccountCaptor.capture(),
                        creditAccountCaptor.capture(),
                        amountCaptor.capture(),
                        any(LocalDateTime.class),
                        descriptionCaptor.capture());

        assertThat(sourceEventIdCaptor.getValue()).isEqualTo(UUID.fromString(APPLICATION_REQUEST_ID));
        assertThat(debitAccountCaptor.getValue()).isEqualTo(undepositedFundsAccountId);
        assertThat(creditAccountCaptor.getValue()).isEqualTo(arAccountId);
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("500.00");
        assertThat(descriptionCaptor.getValue()).contains(APPLICATION_REQUEST_ID);
    }

    @Test
    @DisplayName("happy path registers idempotency key after successful post")
    void happyPath_registersIdempotencyKey() {
        stubAccountResolution();
        when(glPostingService.postPaymentApplication(
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any(BigDecimal.class),
                        any(LocalDateTime.class),
                        any(String.class)))
                .thenReturn(postedEntry);

        handler.onPaymentApplicationGLPosting(testEvent);

        verify(idempotencyService).registerKey(IDEMPOTENCY_KEY, journalEntryId);
    }

    @Test
    @DisplayName("duplicate work item no-ops when idempotency key already processed")
    void duplicateWorkItem_noOps() {
        when(idempotencyService.isKeyProcessed(IDEMPOTENCY_KEY)).thenReturn(true);

        handler.onPaymentApplicationGLPosting(testEvent);

        verifyNoInteractions(glPostingService, glMappingResolver);
        verify(idempotencyService, never()).registerKey(any(String.class), any(UUID.class));
    }

    @Test
    @DisplayName("missing PAYMENT_APPLICATION posting configuration fails without registering idempotency key")
    void missingPostingConfiguration_failsRetryable() {
        when(glMappingResolver.resolveGLAccount(
                        eq("PAYMENT_APPLICATION"), eq("UNDEPOSITED_FUNDS"), any(LocalDateTime.class)))
                .thenThrow(new IllegalArgumentException("Posting category not configured: PAYMENT_APPLICATION"));

        assertThatThrownBy(() -> handler.onPaymentApplicationGLPosting(testEvent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(APPLICATION_REQUEST_ID)
                .cause()
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(glPostingService);
        verify(idempotencyService, never()).registerKey(any(String.class), any(UUID.class));
    }

    @Test
    @DisplayName("unresolvable GL mapping fails without posting or registering idempotency key")
    void unresolvableMapping_failsRetryable() {
        when(glMappingResolver.resolveGLAccount(
                        eq("PAYMENT_APPLICATION"), eq("UNDEPOSITED_FUNDS"), any(LocalDateTime.class)))
                .thenReturn(undepositedFundsAccountId);
        when(glMappingResolver.resolveGLAccount(
                        eq("PAYMENT_APPLICATION"), eq("ACCOUNTS_RECEIVABLE"), any(LocalDateTime.class)))
                .thenThrow(new IllegalArgumentException("No GL mapping found"));

        assertThatThrownBy(() -> handler.onPaymentApplicationGLPosting(testEvent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(APPLICATION_REQUEST_ID)
                .cause()
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(glPostingService);
        verify(idempotencyService, never()).registerKey(any(String.class), any(UUID.class));
    }

    @Test
    @DisplayName("period-closed posting failure propagates unwrapped and does not register idempotency key")
    void periodClosed_propagatesForRetry() {
        stubAccountResolution();
        when(glPostingService.postPaymentApplication(
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any(BigDecimal.class),
                        any(LocalDateTime.class),
                        any(String.class)))
                .thenThrow(new AccountingPeriodClosedException("2026-03", "Period 2026-03 is CLOSED"));

        assertThatThrownBy(() -> handler.onPaymentApplicationGLPosting(testEvent))
                .isInstanceOf(AccountingPeriodClosedException.class);

        verify(idempotencyService, never()).registerKey(any(String.class), any(UUID.class));
    }

    @Test
    @DisplayName("F4: transaction date and mapping resolution derive from applicationTimestamp, not clock-now (outbox backlog)")
    void backlogRetry_usesApplicationTimestampNotClockNow() {
        // Simulate an outbox retry / backlog: the payment application actually
        // happened weeks BEFORE the clock's "now". The JE transaction date and
        // the effective-dated GL mapping resolution must both anchor to the
        // business (application) date, never the later processing clock time —
        // otherwise the entry lands in the wrong accounting period and selects
        // the wrong effective-dated mapping (F4, PR #974).
        Instant applicationInstant = Instant.parse("2026-02-01T08:30:00Z");
        LocalDateTime expectedTransactionDate = LocalDateTime.ofInstant(applicationInstant, clock.getZone());
        LocalDateTime clockNow = LocalDateTime.now(clock);

        // Guard: the two dates must genuinely differ so the assertions below
        // distinguish application-date provenance from clock-now provenance.
        assertThat(expectedTransactionDate).isNotEqualTo(clockNow);

        PaymentApplicationGLPostingEvent backlogEvent = PaymentApplicationGLPostingEvent.builder()
                .eventId(testEvent.getEventId())
                .applicationRequestId(APPLICATION_REQUEST_ID)
                .paymentId(testEvent.getPaymentId())
                .customerId(testEvent.getCustomerId())
                .currency("USD")
                .appliedAmount(new BigDecimal("500.00"))
                .applicationTimestamp(applicationInstant)
                .build();

        stubAccountResolution();
        when(glPostingService.postPaymentApplication(
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any(BigDecimal.class),
                        any(LocalDateTime.class),
                        any(String.class)))
                .thenReturn(postedEntry);

        handler.onPaymentApplicationGLPosting(backlogEvent);

        // Mapping resolution effective date == application-derived date (both legs).
        ArgumentCaptor<LocalDateTime> mappingDateCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(glMappingResolver)
                .resolveGLAccount(eq("PAYMENT_APPLICATION"), eq("UNDEPOSITED_FUNDS"), mappingDateCaptor.capture());
        assertThat(mappingDateCaptor.getValue()).isEqualTo(expectedTransactionDate).isNotEqualTo(clockNow);

        verify(glMappingResolver)
                .resolveGLAccount(eq("PAYMENT_APPLICATION"), eq("ACCOUNTS_RECEIVABLE"), eq(expectedTransactionDate));

        // Journal entry transaction date == application-derived date, NOT clock-now.
        ArgumentCaptor<LocalDateTime> postDateCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(glPostingService)
                .postPaymentApplication(
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any(BigDecimal.class),
                        postDateCaptor.capture(),
                        any(String.class));
        assertThat(postDateCaptor.getValue()).isEqualTo(expectedTransactionDate).isNotEqualTo(clockNow);
    }

    @Test
    @DisplayName("non-UUID application request id derives a deterministic name-based sourceEventId")
    void nonUuidRequestId_derivesDeterministicSourceEventId() {
        stubAccountResolution();
        when(glPostingService.postPaymentApplication(
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any(BigDecimal.class),
                        any(LocalDateTime.class),
                        any(String.class)))
                .thenReturn(postedEntry);

        PaymentApplicationGLPostingEvent nonUuidEvent = PaymentApplicationGLPostingEvent.builder()
                .eventId(testEvent.getEventId())
                .applicationRequestId("REQ-2026-000123")
                .paymentId(testEvent.getPaymentId())
                .customerId(testEvent.getCustomerId())
                .currency("USD")
                .appliedAmount(new BigDecimal("500.00"))
                .applicationTimestamp(testEvent.getApplicationTimestamp())
                .build();

        handler.onPaymentApplicationGLPosting(nonUuidEvent);

        UUID expected = UUID.nameUUIDFromBytes("REQ-2026-000123".getBytes(StandardCharsets.UTF_8));
        verify(glPostingService)
                .postPaymentApplication(
                        eq(expected),
                        any(UUID.class),
                        any(UUID.class),
                        any(BigDecimal.class),
                        any(LocalDateTime.class),
                        any(String.class));
    }
}
