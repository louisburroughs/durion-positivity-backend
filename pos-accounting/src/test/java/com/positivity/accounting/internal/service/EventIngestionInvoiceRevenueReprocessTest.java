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
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
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
 * CAP:550 S32d (AW50): an invoice held {@code TAX_TYPE_MISSING} is reprocessed through {@link
 * InvoiceRevenueReprocessor}, never the posting engine.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventIngestionServiceImpl - reprocessing an invoice held TAX_TYPE_MISSING (CAP:550 S32d)")
class EventIngestionInvoiceRevenueReprocessTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0199b000-0000-7000-8000-0000000000d1");

    @Spy
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private AccountingEventRepository accountingEventRepository;

    @Mock
    private ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

    @Mock
    private PostingEngineOrchestrator postingEngineOrchestrator;

    @Mock
    private InvoiceRevenueReprocessor invoiceRevenueReprocessor;

    @InjectMocks
    private EventIngestionServiceImpl service;

    private AccountingEvent event;
    private final Map<String, Object> payload =
            Map.of("invoiceId", UUID.randomUUID().toString());

    @BeforeEach
    void setUp() {
        event = new AccountingEvent();
        event.setEventId(EVENT_ID);
        event.setEventType(InvoiceUpdatedV1.EVENT_TYPE);
        event.setSourceSystem("pos-invoice");
        event.setStatus(AccountingEventStatus.SUSPENDED);
        event.setFailureReasonCode("TAX_TYPE_MISSING");
        event.setFailureDetails("untyped tax");
        event.setAttemptCount(0);
        event.setPayload(payload);
        event.setTransactionDate(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        event.setReceivedAt(NOW);
        when(accountingEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("still untyped: re-suspended TAX_TYPE_MISSING with the new detail, never through the engine")
    void stillUntypedStaysHeld() {
        when(invoiceRevenueReprocessor.reprocess(payload))
                .thenReturn(new InvoiceRevenueReprocessor.Result(
                        AccountingEventStatus.SUSPENDED, "TAX_TYPE_MISSING", "still untyped", null, null));

        AccountingEventResponse response = reprocess();

        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(event.getFailureReasonCode()).isEqualTo("TAX_TYPE_MISSING");
        assertThat(event.getFailureDetails()).isEqualTo("still untyped");
        assertThat(event.getAttemptCount()).isEqualTo(1);
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);
    }

    @Test
    @DisplayName("typed now: PROCESSED / NEW linked to the entry, attempt recorded SUCCESS")
    void typedNowPosts() {
        UUID entry = UUID.randomUUID();
        when(invoiceRevenueReprocessor.reprocess(payload))
                .thenReturn(new InvoiceRevenueReprocessor.Result(
                        AccountingEventStatus.PROCESSED, null, "posted by type", entry, IdempotencyOutcome.NEW));

        assertThat(reprocess().getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(event.getJournalEntryId()).isEqualTo(entry);
        assertThat(event.getFailureReasonCode()).isNull();
        assertThat(event.getProcessedAt()).isEqualTo(NOW);
        assertThat(history().getOutcome()).isEqualTo(ReprocessingOutcome.SUCCESS);
        verify(postingEngineOrchestrator, never()).processEvent(any(), any(), any(), anyBoolean());
    }

    private AccountingEventResponse reprocess() {
        return service.reprocessEvent(EVENT_ID, new ReprocessEventRequest(), "ops-user");
    }

    private ReprocessingAttemptHistory history() {
        ArgumentCaptor<ReprocessingAttemptHistory> attempt = ArgumentCaptor.forClass(ReprocessingAttemptHistory.class);
        verify(reprocessingAttemptHistoryRepository).save(attempt.capture());
        return attempt.getValue();
    }
}
