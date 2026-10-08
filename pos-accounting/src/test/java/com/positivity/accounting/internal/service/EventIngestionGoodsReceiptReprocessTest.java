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
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
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

/**
 * A held {@code goodsreceipt.recorded} row is reprocessed through {@link GoodsReceiptReprocessor}, never the posting
 * engine (CAP:550 S41, #2602; Accounting ruling 4).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventIngestionServiceImpl - reprocessing a held goods receipt (#2602)")
class EventIngestionGoodsReceiptReprocessTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0199b000-0000-7000-8000-000000000041");

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
    private GoodsReceiptReprocessor goodsReceiptReprocessor;

    @InjectMocks
    private EventIngestionServiceImpl service;

    private AccountingEvent event;
    private final Map<String, Object> payload =
            Map.of("receiptId", UUID.randomUUID().toString());

    @BeforeEach
    void setUp() {
        event = new AccountingEvent();
        event.setEventId(EVENT_ID);
        event.setEventType(GoodsReceiptRecordedV1.EVENT_TYPE);
        event.setSourceSystem("pos-inventory");
        event.setStatus(AccountingEventStatus.SUSPENDED);
        event.setFailureReasonCode("CURRENCY_NOT_SUPPORTED");
        event.setFailureDetails("states no currency");
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
    @DisplayName("a currency-less receipt re-suspends CURRENCY_NOT_SUPPORTED, never through the posting engine")
    void currencyHoldStaysHeld() {
        when(goodsReceiptReprocessor.reprocess(payload))
                .thenReturn(new GoodsReceiptReprocessor.Result(
                        AccountingEventStatus.SUSPENDED, "CURRENCY_NOT_SUPPORTED", "states no currency", null, null));

        AccountingEventResponse response = reprocess();

        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(event.getFailureReasonCode()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getResolvedByUserId()).isEqualTo("ops-user");
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);
    }

    @Test
    @DisplayName("a malformed receipt re-suspends VALIDATION_ERROR with its detail, never NO_RULE_VERSION")
    void malformedStaysHeld() {
        event.setFailureReasonCode("VALIDATION_ERROR");
        when(goodsReceiptReprocessor.reprocess(payload))
                .thenReturn(new GoodsReceiptReprocessor.Result(
                        AccountingEventStatus.SUSPENDED, "VALIDATION_ERROR", "lines do not sum", null, null));

        assertThat(reprocess().getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(event.getFailureReasonCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(event.getFailureDetails()).isEqualTo("lines do not sum");
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a receipt that now passes is PROCESSED / NEW with its entry; a re-post is DUPLICATE_IGNORED")
    void postedThenDuplicate() {
        UUID entry = UUID.randomUUID();
        when(goodsReceiptReprocessor.reprocess(payload))
                .thenReturn(new GoodsReceiptReprocessor.Result(
                        AccountingEventStatus.PROCESSED, null, "posted", entry, IdempotencyOutcome.NEW));

        assertThat(reprocess().getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(event.getJournalEntryId()).isEqualTo(entry);
        assertThat(event.getFailureReasonCode()).isNull();
        assertThat(event.getFailureDetails()).isNull();
        assertThat(event.getProcessedAt()).isEqualTo(NOW);
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.SUCCESS);

        event.setStatus(AccountingEventStatus.SUSPENDED);
        when(goodsReceiptReprocessor.reprocess(payload))
                .thenReturn(new GoodsReceiptReprocessor.Result(
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
    @DisplayName("two reprocesses at once: the loser's unique-key or version conflict is the deterministic 409 message")
    void concurrentReprocessIsAConflict() {
        when(goodsReceiptReprocessor.reprocess(payload))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("idempotency_keys_key_value"));

        org.assertj.core.api.Assertions.assertThatThrownBy(this::reprocess)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Concurrent reprocessing detected for event " + EVENT_ID)
                .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        org.mockito.Mockito.reset(goodsReceiptReprocessor);
        when(goodsReceiptReprocessor.reprocess(payload))
                .thenReturn(new GoodsReceiptReprocessor.Result(
                        AccountingEventStatus.SUSPENDED, "CURRENCY_NOT_SUPPORTED", "states no currency", null, null));
        org.mockito.Mockito.doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        AccountingEvent.class, EVENT_ID))
                .when(accountingEventRepository)
                .save(any(AccountingEvent.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(this::reprocess)
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
