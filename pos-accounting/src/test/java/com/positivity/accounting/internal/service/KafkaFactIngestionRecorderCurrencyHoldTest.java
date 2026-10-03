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
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link KafkaFactIngestionRecorder#recordCurrencyHeld} (ADR-0067 PC-9, issue #2312): a fact in
 * a currency other than the ledger's is held as one visible {@code SUSPENDED /
 * CURRENCY_NOT_SUPPORTED} record (releasable through the audited reprocess path, #2334), and a
 * redelivery does not write a second.
 */
class KafkaFactIngestionRecorderCurrencyHoldTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final LocalDateTime TX_DATE = LocalDateTime.of(2026, 7, 23, 18, 30);
    private static final String EVENT_TYPE = "order.session.closed";
    private static final String ENVELOPE_EVENT_ID = "01960003-0000-7000-8000-0000000000e1";

    private final AccountingEventRepository accountingEventRepository = mock(AccountingEventRepository.class);
    private final AccountingSequenceLocker sequenceLocker = mock(AccountingSequenceLocker.class);

    private KafkaFactIngestionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new KafkaFactIngestionRecorder(
                CLOCK,
                new ObjectMapper(),
                accountingEventRepository,
                sequenceLocker,
                mock(JournalEntryRepository.class));
    }

    @Test
    @DisplayName("First delivery writes one SUSPENDED / CURRENCY_NOT_SUPPORTED record under the producer (#2334)")
    void firstDeliveryIsHeldVisibly() {
        when(accountingEventRepository.existsByEventTypeAndDomainKeyIdAndFailureReasonCode(
                        EVENT_TYPE, SESSION_ID.toString(), "CURRENCY_NOT_SUPPORTED"))
                .thenReturn(false);
        when(accountingEventRepository.save(any())).thenAnswer(invocation -> {
            AccountingEvent event = invocation.getArgument(0);
            event.onPrePersist();
            return event;
        });
        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey("AE-202607");
        sequence.setNextValue(1L);
        when(sequenceLocker.lockOrProvision(anyString())).thenReturn(sequence);

        boolean recorded = recorder.recordCurrencyHeld(
                "pos-order",
                EVENT_TYPE,
                ENVELOPE_EVENT_ID,
                SESSION_ID,
                TX_DATE,
                Map.of("currencyCode", "EUR"),
                "held: EUR");

        assertThat(recorded).isTrue();
        ArgumentCaptor<AccountingEvent> saved = ArgumentCaptor.forClass(AccountingEvent.class);
        verify(accountingEventRepository).save(saved.capture());
        AccountingEvent event = saved.getValue();
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(event.getFailureReasonCode()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(event.getErrorMessage()).contains("EUR");
        assertThat(event.getSourceSystem()).isEqualTo("pos-order");
        assertThat(event.getDomainKeyId()).isEqualTo(SESSION_ID.toString());
        assertThat(event.getJournalEntryId()).isNull();
        assertThat(event.getIngestionId()).isEqualTo(UUID.fromString(ENVELOPE_EVENT_ID));
    }

    @Test
    @DisplayName("A redelivered fact already held writes no second record")
    void redeliveryIsNotRecordedTwice() {
        when(accountingEventRepository.existsByEventTypeAndDomainKeyIdAndFailureReasonCode(
                        EVENT_TYPE, SESSION_ID.toString(), "CURRENCY_NOT_SUPPORTED"))
                .thenReturn(true);

        boolean recorded = recorder.recordCurrencyHeld(
                "pos-order",
                EVENT_TYPE,
                ENVELOPE_EVENT_ID,
                SESSION_ID,
                TX_DATE,
                Map.of("currencyCode", "EUR"),
                "held: EUR");

        assertThat(recorded).isFalse();
        verify(accountingEventRepository, never()).save(any());
    }
}
