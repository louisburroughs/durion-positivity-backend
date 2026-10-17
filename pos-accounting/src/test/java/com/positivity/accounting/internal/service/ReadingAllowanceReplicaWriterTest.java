package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.BillIntakeReadingAllowance;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.BillIntakeReadingAllowanceRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The reading-allowance replica write (#2619): upsert, version guard, replay. */
@DisplayName("ReadingAllowanceReplicaWriter")
class ReadingAllowanceReplicaWriterTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000b2");
    private static final String EVENT_ID = "01990000-0000-7000-8000-0000000000e1";
    private static final Instant EFFECTIVE = Instant.parse("2026-10-17T00:00:00Z");

    private final BillIntakeReadingAllowanceRepository allowances = mock(BillIntakeReadingAllowanceRepository.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final Clock clock = Clock.fixed(EFFECTIVE, ZoneOffset.UTC);
    private final ReadingAllowanceReplicaWriter writer =
            new ReadingAllowanceReplicaWriter(allowances, processedEvents, clock);

    @Test
    @DisplayName("a first fact inserts the row keyed by the tenant id and records the eventId")
    void insertsAndRecords() {
        when(allowances.findById(TENANT)).thenReturn(Optional.empty());

        writer.applyAndRecord(fact(2, "STANDARD", "20.00"));

        ArgumentCaptor<BillIntakeReadingAllowance> saved = ArgumentCaptor.forClass(BillIntakeReadingAllowance.class);
        verify(allowances).save(saved.capture());
        BillIntakeReadingAllowance row = saved.getValue();
        assertThat(row.getReadingAllowanceId()).isEqualTo(TENANT);
        assertThat(row.getTierCode()).isEqualTo("STANDARD");
        assertThat(row.getAmount()).isEqualByComparingTo("20.00");
        assertThat(row.getCurrency()).isEqualTo("USD");
        assertThat(row.getEffectiveAt()).isEqualTo(EFFECTIVE);
        assertThat(row.getAggregateVersion()).isEqualTo(2);
        ArgumentCaptor<ProcessedEvent> event = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(event.capture());
        assertThat(event.getValue().getEventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("a newer fact overwrites the stored allowance")
    void newerOverwrites() {
        BillIntakeReadingAllowance held = row(2, "STANDARD", "20.00");
        when(allowances.findById(TENANT)).thenReturn(Optional.of(held));

        writer.applyAndRecord(fact(3, "PRO", "60.00"));

        assertThat(held.getTierCode()).isEqualTo("PRO");
        assertThat(held.getAmount()).isEqualByComparingTo("60.00");
        assertThat(held.getAggregateVersion()).isEqualTo(3);
        verify(allowances).save(held);
    }

    @Test
    @DisplayName("an equal version applies, so replay repairs a bad row")
    void equalVersionApplies() {
        BillIntakeReadingAllowance held = row(3, "STANDARD", "20.00");
        when(allowances.findById(TENANT)).thenReturn(Optional.of(held));

        writer.applyAndRecord(fact(3, "PRO", "60.00"));

        assertThat(held.getTierCode()).isEqualTo("PRO");
        verify(allowances).save(held);
    }

    @Test
    @DisplayName("an older fact is ignored, but its eventId is still recorded")
    void olderIsIgnored() {
        BillIntakeReadingAllowance held = row(5, "PRO", "60.00");
        when(allowances.findById(TENANT)).thenReturn(Optional.of(held));

        writer.applyAndRecord(fact(2, "STANDARD", "20.00"));

        assertThat(held.getTierCode()).isEqualTo("PRO");
        assertThat(held.getAmount()).isEqualByComparingTo("60.00");
        verify(allowances, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("a replayed eventId changes nothing")
    void replayedEventIdIsSkipped() {
        when(processedEvents.existsById(EVENT_ID)).thenReturn(true);

        writer.applyAndRecord(fact(2, "STANDARD", "20.00"));

        verifyNoInteractions(allowances);
        verify(processedEvents, never()).save(any());
    }

    private static BillIntakeReadingAllowance row(long version, String tier, String amount) {
        BillIntakeReadingAllowance row = new BillIntakeReadingAllowance();
        row.setReadingAllowanceId(TENANT);
        row.setTierCode(tier);
        row.setAmount(new BigDecimal(amount));
        row.setCurrency("USD");
        row.setEffectiveAt(EFFECTIVE);
        row.setAggregateVersion(version);
        return row;
    }

    private static ReadingAllowanceEvent fact(long version, String tier, String amount) {
        return new ReadingAllowanceEvent(EVENT_ID, version, TENANT, tier, new BigDecimal(amount), "USD", EFFECTIVE);
    }
}
