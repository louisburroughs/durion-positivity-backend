package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * A held {@code order.session.closed} row is reprocessed through {@link RegisterSessionCloseReprocessor}, never the
 * posting engine (#2594, CAP:550 S17 PROPOSED 6): by the audited reprocess, by {@code retryAccountingEvent} and so by
 * the scheduled retry, whatever the row's reason.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventIngestionServiceImpl - reprocessing a held register session (#2594)")
class EventIngestionRegisterSessionReprocessTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0199b000-0000-7000-8000-000000002594");
    private static final UUID INGESTION_ID = UUID.fromString("0199b000-0000-7000-8000-0000000025e1");

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
    private RegisterSessionCloseReprocessor registerSessionCloseReprocessor;

    @InjectMocks
    private EventIngestionServiceImpl service;

    private AccountingEvent event;
    private final Map<String, Object> payload =
            Map.of("sessionId", UUID.randomUUID().toString());

    @BeforeEach
    void setUp() {
        event = new AccountingEvent();
        event.setEventId(EVENT_ID);
        event.setIngestionId(INGESTION_ID);
        event.setEventType(RegisterSessionClosedV1.EVENT_TYPE);
        event.setSourceSystem("pos-order");
        event.setStatus(AccountingEventStatus.SUSPENDED);
        event.setFailureReasonCode("GL_MAPPING_NOT_CONFIGURED");
        event.setFailureDetails("Mapping key not configured: REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_SHOP_SUPPLIES");
        event.setAttemptCount(0);
        event.setPayload(payload);
        event.setTransactionDate(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        event.setReceivedAt(NOW);
        when(accountingEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        // Lenient: the concurrency case re-stubs save to throw.
        org.mockito.Mockito.lenient()
                .when(accountingEventRepository.save(any(AccountingEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a mapping still missing re-suspends GL_MAPPING_NOT_CONFIGURED, counted and audited, never through the"
            + " posting engine; the reprocessor gets the payload, the held reason and the envelope id")
    void mappingStillMissingStaysHeld() {
        when(registerSessionCloseReprocessor.reprocess(payload, "GL_MAPPING_NOT_CONFIGURED", INGESTION_ID.toString()))
                .thenReturn(new RegisterSessionCloseReprocessor.Result(
                        AccountingEventStatus.SUSPENDED,
                        "GL_MAPPING_NOT_CONFIGURED",
                        "still no PETTY_EXPENSE_SHOP_SUPPLIES mapping",
                        null,
                        null));

        AccountingEventResponse response = reprocess();

        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(event.getFailureReasonCode()).isEqualTo("GL_MAPPING_NOT_CONFIGURED");
        assertThat(event.getFailureDetails()).isEqualTo("still no PETTY_EXPENSE_SHOP_SUPPLIES mapping");
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getResolvedByUserId()).isEqualTo("ops-user");
        assertThat(event.getIdempotencyOutcome()).isNull();
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a session that now posts is PROCESSED / NEW with its entry; a re-post is DUPLICATE_IGNORED")
    void postedThenDuplicate() {
        UUID entry = UUID.randomUUID();
        when(registerSessionCloseReprocessor.reprocess(eq(payload), any(), any()))
                .thenReturn(new RegisterSessionCloseReprocessor.Result(
                        AccountingEventStatus.PROCESSED, null, "posted", entry, IdempotencyOutcome.NEW));

        assertThat(reprocess().getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(event.getJournalEntryId()).isEqualTo(entry);
        assertThat(event.getFailureReasonCode()).isNull();
        assertThat(event.getFailureDetails()).isNull();
        assertThat(event.getErrorMessage()).isNull();
        assertThat(event.getProcessedAt()).isEqualTo(NOW);
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.SUCCESS);

        event.setStatus(AccountingEventStatus.SUSPENDED);
        when(registerSessionCloseReprocessor.reprocess(eq(payload), any(), any()))
                .thenReturn(new RegisterSessionCloseReprocessor.Result(
                        AccountingEventStatus.PROCESSED,
                        null,
                        "already posted",
                        entry,
                        IdempotencyOutcome.DUPLICATE_IGNORED));
        assertThat(reprocess().getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("DUPLICATE_IGNORED");
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a FAILED / VALIDATION_ERROR session row retried through retryAccountingEvent goes to the reprocessor"
            + " too, and a currency hold reprocessed by hand does as well: the engine never runs a session fact")
    void everySessionRowIsRouted() {
        event.setStatus(AccountingEventStatus.FAILED);
        event.setFailureReasonCode("VALIDATION_ERROR");
        event.setIngestionId(null);
        when(registerSessionCloseReprocessor.reprocess(payload, "VALIDATION_ERROR", EVENT_ID.toString()))
                .thenReturn(new RegisterSessionCloseReprocessor.Result(
                        AccountingEventStatus.FAILED, "VALIDATION_ERROR", "no movementId", null, null));

        assertThat(service.retryEventProcessing(EVENT_ID).getStatus()).isEqualTo(AccountingEventStatus.FAILED);
        assertThat(event.getFailureReasonCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(event.getErrorMessage()).isEqualTo("no movementId");

        event.setStatus(AccountingEventStatus.SUSPENDED);
        event.setFailureReasonCode("CURRENCY_NOT_SUPPORTED");
        when(registerSessionCloseReprocessor.reprocess(payload, "CURRENCY_NOT_SUPPORTED", EVENT_ID.toString()))
                .thenReturn(new RegisterSessionCloseReprocessor.Result(
                        AccountingEventStatus.SUSPENDED, "CURRENCY_NOT_SUPPORTED", "EUR", null, null));
        assertThat(reprocess().getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("two reprocesses at once: the loser's unique-key or version conflict is the deterministic 409 message")
    void concurrentReprocessIsAConflict() {
        when(registerSessionCloseReprocessor.reprocess(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uq_undeposited_session_session"));

        assertThatThrownBy(this::reprocess)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Concurrent reprocessing detected for event " + EVENT_ID)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);

        org.mockito.Mockito.reset(registerSessionCloseReprocessor);
        when(registerSessionCloseReprocessor.reprocess(any(), any(), any()))
                .thenReturn(new RegisterSessionCloseReprocessor.Result(
                        AccountingEventStatus.PROCESSED, null, "posted", UUID.randomUUID(), IdempotencyOutcome.NEW));
        org.mockito.Mockito.doThrow(new ObjectOptimisticLockingFailureException(AccountingEvent.class, EVENT_ID))
                .when(accountingEventRepository)
                .save(any(AccountingEvent.class));

        assertThatThrownBy(this::reprocess)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Concurrent reprocessing detected");
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
    }

    private AccountingEventResponse reprocess() {
        return service.reprocessEvent(EVENT_ID, new ReprocessEventRequest(), "ops-user");
    }

    private ReprocessingAttemptHistory history() {
        ArgumentCaptor<ReprocessingAttemptHistory> attempt = ArgumentCaptor.forClass(ReprocessingAttemptHistory.class);
        verify(reprocessingAttemptHistoryRepository, org.mockito.Mockito.atLeastOnce())
                .save(attempt.capture());
        return attempt.getValue();
    }
}
