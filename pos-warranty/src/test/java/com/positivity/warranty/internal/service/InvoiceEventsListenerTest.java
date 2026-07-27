package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.entity.ExtInvoiceLineReplica;
import com.positivity.warranty.internal.entity.ExtInvoiceReplica;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ExtInvoiceLineReplicaRepository;
import com.positivity.warranty.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer contract of the invoice.events.v1 listener (issue #924): manual envelope parse,
 * processed_events dedupe, strictly-below stale guard on aggregateVersion, full line-set
 * replacement, transient DB errors rethrown for container retry/DLQ.
 */
class InvoiceEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000a01");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000a02");
    private static final UUID ITEM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000a03");
    private static final String PARTY_ID = "018f0000-0000-7000-8000-000000000a04";

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtInvoiceReplicaRepository extInvoice = mock(ExtInvoiceReplicaRepository.class);
    private final ExtInvoiceLineReplicaRepository extInvoiceLine = mock(ExtInvoiceLineReplicaRepository.class);

    private InvoiceEventsListener listener;

    @BeforeEach
    void setUp() {
        listener =
                new InvoiceEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, extInvoice, extInvoiceLine);
    }

    private static String updatedEvent(String eventId, long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"invoice.invoice.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"invoiceId":"%s","invoiceNumber":"INV-100","workorderId":"%s","partyId":"%s",
                            "status":"PAID","createdAt":"2026-07-01T09:00:00Z",
                            "lines":[{"invoiceItemId":"%s","description":"Alternator","quantity":1,
                                      "unitPrice":199.99,"amount":199.99,"itemType":"PART"}]}}
                """.formatted(eventId, INVOICE_ID, aggregateVersion, INVOICE_ID, WORKORDER_ID, PARTY_ID, ITEM_ID);
    }

    @Test
    @DisplayName("Upserts header + replaces line set from an invoice.invoice.updated fact")
    void upsertsReplica() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(extInvoice.findById(INVOICE_ID)).thenReturn(Optional.empty());

        listener.onInvoiceEvent(updatedEvent("e-1", 5));

        ArgumentCaptor<ExtInvoiceReplica> header = ArgumentCaptor.captor();
        verify(extInvoice).save(header.capture());
        assertThat(header.getValue().getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(header.getValue().getPartyId()).isEqualTo(PARTY_ID);
        assertThat(header.getValue().getAggregateVersion()).isEqualTo(5L);

        verify(extInvoiceLine).deleteByInvoiceId(INVOICE_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExtInvoiceLineReplica>> lines = ArgumentCaptor.captor();
        verify(extInvoiceLine).saveAll(lines.capture());
        assertThat(lines.getValue()).hasSize(1);
        assertThat(lines.getValue().getFirst().getInvoiceItemId()).isEqualTo(ITEM_ID);

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.captor();
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getOwner()).isEqualTo(InvoiceEventsListener.OWNER);
    }

    @Test
    @DisplayName("Drops a stale fact (older aggregateVersion) but still records it processed")
    void dropsStaleFact() {
        when(processedEvents.existsById("e-stale")).thenReturn(false);
        when(extInvoice.findById(INVOICE_ID))
                .thenReturn(Optional.of(ExtInvoiceReplica.builder()
                        .invoiceId(INVOICE_ID)
                        .aggregateVersion(9L)
                        .updatedAt(Instant.parse("2026-07-14T00:00:00Z"))
                        .build()));

        listener.onInvoiceEvent(updatedEvent("e-stale", 5));

        verify(extInvoice, never()).save(any());
        verify(extInvoiceLine, never()).deleteByInvoiceId(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onInvoiceEvent(updatedEvent("e-dup", 5));

        verify(extInvoice, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Ignores other event types but still records them processed")
    void ignoresOtherEventTypesButRecordsThem() {
        when(processedEvents.existsById("e-2")).thenReturn(false);

        listener.onInvoiceEvent("""
                {"eventId":"e-2","eventType":"invoice.billing-rules.updated","payload":{}}
                """);

        verify(extInvoice, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        when(extInvoice.findById(INVOICE_ID)).thenReturn(Optional.empty());
        when(extInvoice.save(any())).thenThrow(new QueryTimeoutException("db"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onInvoiceEvent(updatedEvent("e-4", 5)));

        verify(processedEvents, never()).save(any());
    }
}
