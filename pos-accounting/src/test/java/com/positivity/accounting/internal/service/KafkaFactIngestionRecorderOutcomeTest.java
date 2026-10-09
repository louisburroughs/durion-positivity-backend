package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link KafkaFactIngestionRecorder#record} (issue #2433): each {@link FactPostingOutcome} a
 * posting path reports becomes the fact's one {@code accounting_event} row, under the producer
 * the listener names.
 */
class KafkaFactIngestionRecorderOutcomeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final LocalDateTime TX_DATE = LocalDateTime.of(2026, 10, 2, 9, 30);
    private static final String EVENT_TYPE = "invoice.invoice.updated";
    private static final String ENVELOPE_EVENT_ID = "01960003-0000-7000-8000-0000000000f1";

    private final AccountingEventRepository accountingEventRepository = mock(AccountingEventRepository.class);
    private final AccountingSequenceLocker sequenceLocker = mock(AccountingSequenceLocker.class);
    private final JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);

    private KafkaFactIngestionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new KafkaFactIngestionRecorder(
                CLOCK, new ObjectMapper(), accountingEventRepository, sequenceLocker, journalEntryRepository);
        when(accountingEventRepository.save(any())).thenAnswer(invocation -> {
            AccountingEvent event = invocation.getArgument(0);
            event.onPrePersist();
            return event;
        });
        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey("AE-202610");
        sequence.setNextValue(7L);
        when(sequenceLocker.lockOrProvision(anyString())).thenReturn(sequence);
    }

    @Test
    @DisplayName("Posted: PROCESSED / NEW, linked to the entry, under the named producer")
    void postedIsProcessedNew() {
        AccountingEvent saved = record(FactPostingOutcome.posted(JOURNAL_ENTRY_ID));

        assertThat(saved.getSourceSystem()).isEqualTo("pos-invoice");
        assertThat(saved.getEventType()).isEqualTo(EVENT_TYPE);
        assertThat(saved.getDomainKeyId()).isEqualTo(INVOICE_ID.toString());
        assertThat(saved.getIngestionId()).isEqualTo(UUID.fromString(ENVELOPE_EVENT_ID));
        assertThat(saved.getTransactionDate()).isEqualTo(TX_DATE);
        assertThat(saved.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(saved.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(saved.getJournalEntryId()).isEqualTo(JOURNAL_ENTRY_ID);
        assertThat(saved.getEventReference()).isEqualTo("AE-202610-7");
        assertThat(saved.getPayload()).containsEntry("status", "FINALIZED");
    }

    @Test
    @DisplayName("AlreadyPosted with a known entry: PROCESSED / DUPLICATE_IGNORED, linked to that entry")
    void alreadyPostedWithEntry() {
        AccountingEvent saved = record(new FactPostingOutcome.AlreadyPosted(JOURNAL_ENTRY_ID, null));

        assertThat(saved.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(saved.getIdempotencyOutcome()).isEqualTo("DUPLICATE_IGNORED");
        assertThat(saved.getJournalEntryId()).isEqualTo(JOURNAL_ENTRY_ID);
        verify(journalEntryRepository, never()).findBySourceEvent(any());
    }

    @Test
    @DisplayName("AlreadyPosted by source event: the earlier entry is looked up and linked")
    void alreadyPostedBySourceEvent() {
        UUID sourceEventId = UUID.randomUUID();
        JournalEntry earlier = new JournalEntry();
        earlier.setJournalEntryId(JOURNAL_ENTRY_ID);
        when(journalEntryRepository.findBySourceEvent(sourceEventId)).thenReturn(List.of(earlier));

        AccountingEvent saved = record(new FactPostingOutcome.AlreadyPosted(null, sourceEventId));

        assertThat(saved.getIdempotencyOutcome()).isEqualTo("DUPLICATE_IGNORED");
        assertThat(saved.getJournalEntryId()).isEqualTo(JOURNAL_ENTRY_ID);
    }

    @Test
    @DisplayName("AlreadyPosted on a path that never posts: DUPLICATE_IGNORED with no entry")
    void alreadyPostedWithoutEntry() {
        AccountingEvent saved = record(new FactPostingOutcome.AlreadyPosted(null, null));

        assertThat(saved.getIdempotencyOutcome()).isEqualTo("DUPLICATE_IGNORED");
        assertThat(saved.getJournalEntryId()).isNull();
    }

    @Test
    @DisplayName("NothingToPost: PROCESSED / NEW with no entry")
    void nothingToPost() {
        AccountingEvent saved = record(FactPostingOutcome.nothingToPost());

        assertThat(saved.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(saved.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(saved.getJournalEntryId()).isNull();
        assertThat(saved.getFailureReasonCode()).isNull();
    }

    @Test
    @DisplayName("Skipped: terminal SKIPPED with its reason code and detail")
    void skipped() {
        AccountingEvent saved = record(FactPostingOutcome.notPostable("deposit-take invoice"));

        assertThat(saved.getStatus()).isEqualTo(AccountingEventStatus.SKIPPED);
        assertThat(saved.getFailureReasonCode()).isEqualTo(PostingFailureReason.NOT_POSTABLE.name());
        assertThat(saved.getErrorMessage()).isEqualTo("deposit-take invoice");
        assertThat(PostingFailureReason.NOT_POSTABLE.isTerminalSkip()).isTrue();
    }

    @Test
    @DisplayName("CurrencyHeld: nothing more is written — the posting path recorded the hold itself")
    void currencyHeldWritesNothing() {
        recorder.record(
                "pos-order",
                "order.session.closed",
                ENVELOPE_EVENT_ID,
                INVOICE_ID,
                TX_DATE,
                Map.of(),
                new FactPostingOutcome.CurrencyHeld());

        verify(accountingEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("CAP:550 S32d: Held is SUSPENDED with its reason and detail, outside the auto-retry loop")
    void heldIsSuspendedTaxTypeMissing() {
        AccountingEvent saved = record(new FactPostingOutcome.Held(
                PostingFailureReason.TAX_TYPE_MISSING, "Invoice tax of 70.00 on rows without a tax type"));

        assertThat(saved.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(saved.getFailureReasonCode()).isEqualTo("TAX_TYPE_MISSING");
        assertThat(saved.getFailureDetails()).contains("without a tax type");
        assertThat(saved.getJournalEntryId()).isNull();
        assertThat(PostingFailureReason.isExcludedFromAutoRetry("TAX_TYPE_MISSING"))
                .isTrue();
        assertThat(PostingFailureReason.TAX_TYPE_MISSING.isTerminalSkip()).isFalse();
    }

    private AccountingEvent record(FactPostingOutcome outcome) {
        recorder.record(
                "pos-invoice",
                EVENT_TYPE,
                ENVELOPE_EVENT_ID,
                INVOICE_ID,
                TX_DATE,
                Map.of("invoiceId", INVOICE_ID.toString(), "status", "FINALIZED"),
                outcome);
        ArgumentCaptor<AccountingEvent> saved = ArgumentCaptor.forClass(AccountingEvent.class);
        verify(accountingEventRepository).save(saved.capture());
        return saved.getValue();
    }
}
