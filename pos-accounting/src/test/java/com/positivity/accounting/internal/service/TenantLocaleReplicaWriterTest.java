package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtTenant;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtTenantRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The locale replica write (#2618): upsert, version guard, replay, absent locale. */
@DisplayName("TenantLocaleReplicaWriter")
class TenantLocaleReplicaWriterTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000b2");
    private static final String EVENT_ID = "01990000-0000-7000-8000-0000000000e1";

    private final ExtTenantRepository tenants = mock(ExtTenantRepository.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);
    private final TenantLocaleReplicaWriter writer = new TenantLocaleReplicaWriter(tenants, processedEvents, clock);

    @Test
    @DisplayName("a first fact inserts the row keyed by the tenant id and records the eventId")
    void insertsAndRecords() {
        when(tenants.findById(TENANT)).thenReturn(Optional.empty());

        writer.applyAndRecord(fact(2, "fr-CA"));

        ArgumentCaptor<ExtTenant> saved = ArgumentCaptor.forClass(ExtTenant.class);
        verify(tenants).save(saved.capture());
        assertThat(saved.getValue().getExtTenantId()).isEqualTo(TENANT);
        assertThat(saved.getValue().getLocale()).isEqualTo("fr-CA");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(2);
        ArgumentCaptor<ProcessedEvent> event = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(event.capture());
        assertThat(event.getValue().getEventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("a newer fact overwrites the stored locale")
    void newerOverwrites() {
        ExtTenant held = row(2, "en-US");
        when(tenants.findById(TENANT)).thenReturn(Optional.of(held));

        writer.apply(fact(3, "de-DE"));

        assertThat(held.getLocale()).isEqualTo("de-DE");
        assertThat(held.getAggregateVersion()).isEqualTo(3);
        verify(tenants).save(held);
    }

    @Test
    @DisplayName("an older fact is ignored, but its eventId is still recorded")
    void olderIsIgnored() {
        ExtTenant held = row(5, "en-US");
        when(tenants.findById(TENANT)).thenReturn(Optional.of(held));

        writer.applyAndRecord(fact(2, "de-DE"));

        assertThat(held.getLocale()).isEqualTo("en-US");
        verify(tenants, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("a replayed eventId changes nothing")
    void replayedEventIdIsSkipped() {
        when(processedEvents.existsById(EVENT_ID)).thenReturn(true);

        writer.applyAndRecord(fact(2, "de-DE"));

        verifyNoInteractions(tenants);
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("a fact without a locale leaves the stored value")
    void absentLocaleLeavesTheRow() {
        writer.apply(fact(9, null));

        verifyNoInteractions(tenants);
    }

    private static ExtTenant row(long version, String locale) {
        ExtTenant row = new ExtTenant();
        row.setExtTenantId(TENANT);
        row.setLocale(locale);
        row.setAggregateVersion(version);
        return row;
    }

    private static TenantProjectionEvent fact(long version, String locale) {
        return new TenantProjectionEvent(
                EVENT_ID, "tenant.updated", version, TENANT, "acme", "Acme", "ACTIVE", locale);
    }
}
