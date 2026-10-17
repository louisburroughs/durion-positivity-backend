package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.accounting.internal.service.AutomaticPaymentApplicationService.Outcome;
import com.positivity.accounting.internal.service.AutomaticPaymentApplicationService.Result;
import com.positivity.domainevents.payment.PaymentSettledV1;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** Item 6 of #2503: a held settled payment's row is reprocessed by its automatic application. */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventIngestionServiceImpl - reprocessing a held settled payment (#2503)")
class EventIngestionSettledPaymentReprocessTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0199b000-0000-7000-8000-000000000010");

    @Spy
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Spy
    AccountingCalendarZoneResolver zoneResolver = TestZoneResolvers.utc(Clock.fixed(NOW, ZoneOffset.UTC));

    @Mock
    private AccountingEventRepository accountingEventRepository;

    @Mock
    private ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

    @Mock
    private PostingEngineOrchestrator postingEngineOrchestrator;

    @Mock
    private AutomaticPaymentApplicationService automaticPaymentApplicationService;

    @InjectMocks
    private EventIngestionServiceImpl service;

    private AccountingEvent event;
    private final Map<String, Object> payload =
            Map.of("paymentIntentId", UUID.randomUUID().toString());

    @BeforeEach
    void setUp() {
        event = new AccountingEvent();
        event.setEventId(EVENT_ID);
        event.setEventType(PaymentSettledV1.EVENT_TYPE);
        // A Kafka fact row carries the envelope's event id (KafkaFactIngestionRecorder.newEvent).
        event.setIngestionId(UUID.fromString("0199b000-0000-7000-8000-0000000025e2"));
        event.setSourceSystem("pos-invoice");
        event.setStatus(AccountingEventStatus.SUSPENDED);
        event.setFailureReasonCode("PERIOD_CLOSED");
        event.setFailureDetails("settlement date 2026-09-30 is in a closed or hard-locked period");
        event.setAttemptCount(1);
        event.setPayload(payload);
        event.setTransactionDate(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        event.setReceivedAt(NOW);
        when(accountingEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        // Lenient: the #2620 refusal case saves nothing.
        org.mockito.Mockito.lenient()
                .when(accountingEventRepository.save(any(AccountingEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PERIOD_CLOSED", "INVOICE_NOT_FOUND", "INVOICE_NOT_ELIGIBLE"})
    @DisplayName("an application makes the row PROCESSED / NEW, never through the posting engine")
    void appliedBecomesProcessed(String reason) {
        event.setFailureReasonCode(reason);
        when(automaticPaymentApplicationService.reapply(payload)).thenReturn(new Result(Outcome.APPLIED, "applied"));

        AccountingEventResponse response = reprocess("ops-user");

        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(event.getFailureReasonCode()).isNull();
        assertThat(event.getFailureDetails()).isNull();
        assertThat(event.getProcessedAt()).isEqualTo(NOW);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getResolvedByUserId()).isEqualTo("ops-user");
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.SUCCESS);
    }

    @Test
    @DisplayName("a payment another path already settled also resolves the row: PROCESSED")
    void alreadyAppliedBecomesProcessed() {
        when(automaticPaymentApplicationService.reapply(payload))
                .thenReturn(new Result(Outcome.ALREADY_APPLIED, "payment has nothing unapplied"));

        assertThat(reprocess("ops-user").getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
    }

    @Test
    @DisplayName("a party mismatch found on reprocess makes the row SKIPPED / NOT_POSTABLE with the detail")
    void skippedOnReprocess() {
        event.setFailureReasonCode("INVOICE_NOT_FOUND");
        when(automaticPaymentApplicationService.reapply(payload))
                .thenReturn(new Result(Outcome.SKIPPED_PARTY, "customer differs from invoice INV-1"));

        AccountingEventResponse response = reprocess(FailedAccountingEventRetryJob.RETRY_USER);

        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.SKIPPED);
        assertThat(event.getFailureReasonCode()).isEqualTo("NOT_POSTABLE");
        assertThat(event.getErrorMessage()).isEqualTo("customer differs from invoice INV-1");
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);
    }

    @Test
    @DisplayName("still held: the row keeps its hold with the new reason and the attempt counts")
    void stillHeld() {
        event.setFailureReasonCode("INVOICE_NOT_FOUND");
        when(automaticPaymentApplicationService.reapply(payload))
                .thenReturn(new Result(Outcome.SUSPENDED_PERIOD, "settlement date 2026-09-30 is closed"));

        AccountingEventResponse response = reprocess(FailedAccountingEventRetryJob.RETRY_USER);

        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(event.getFailureReasonCode()).isEqualTo("PERIOD_CLOSED");
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);
    }

    @Test
    @DisplayName("an invoice still not replicated spends an attempt: case b shares the retry cap (review #2550)")
    void invoiceNotFoundAgainSpendsAnAttempt() {
        event.setFailureReasonCode("INVOICE_NOT_FOUND");
        when(automaticPaymentApplicationService.reapply(payload))
                .thenReturn(new Result(Outcome.SUSPENDED_INVOICE, "invoice INV-1 is not in the invoice replica yet"));

        AccountingEventResponse response = reprocess(FailedAccountingEventRetryJob.RETRY_USER);

        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(event.getFailureReasonCode()).isEqualTo("INVOICE_NOT_FOUND");
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);
    }

    @Test
    @DisplayName(
            "#2620: a settled payment held for its currency has no release path: 409, the row untouched, no attempt,"
                    + " never the posting engine")
    void currencyHoldIsRefusedNotEngineRouted() {
        event.setFailureReasonCode("CURRENCY_NOT_SUPPORTED");
        event.setFailureDetails("held: CAD");
        event.setPayload(new java.util.HashMap<>(java.util.Map.of("currencyCode", "CAD", "amount", "12.50")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reprocessEvent(
                        EVENT_ID, new com.positivity.accounting.internal.dto.ReprocessEventRequest(), "controller"))
                .isInstanceOfSatisfying(
                        com.positivity.accounting.internal.exception.HeldEventNotReleasableException.class, e -> {
                            org.assertj.core.api.Assertions.assertThat(e.getMessage())
                                    .contains("CURRENCY_NOT_SUPPORTED")
                                    .contains("no release path");
                            org.assertj.core.api.Assertions.assertThat(e.getNextAction())
                                    .contains("CAD")
                                    .contains("Stage B1");
                        });

        org.assertj.core.api.Assertions.assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        org.assertj.core.api.Assertions.assertThat(event.getFailureReasonCode()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        org.assertj.core.api.Assertions.assertThat(event.getAttemptCount()).as("unchanged").isEqualTo(1);
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
        verify(reprocessingAttemptHistoryRepository, never()).save(any());
        verify(accountingEventRepository, never()).save(any());
    }

    private AccountingEventResponse reprocess(String user) {
        return service.reprocessEvent(EVENT_ID, new ReprocessEventRequest(), user);
    }

    private ReprocessingAttemptHistory history() {
        ArgumentCaptor<ReprocessingAttemptHistory> captor = ArgumentCaptor.forClass(ReprocessingAttemptHistory.class);
        verify(reprocessingAttemptHistoryRepository).save(captor.capture());
        return captor.getValue();
    }
}
