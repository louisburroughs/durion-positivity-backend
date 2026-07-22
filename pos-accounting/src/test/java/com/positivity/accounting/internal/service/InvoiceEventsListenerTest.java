package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import java.math.BigDecimal;
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

class InvoiceEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-08T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtInvoiceRepository replica = mock(ExtInvoiceRepository.class);
    private final ExtInvoiceTaxRepository taxReplica = mock(ExtInvoiceTaxRepository.class);

    private InvoiceEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new InvoiceEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, replica, taxReplica);
    }

    private String eventWithBreakdown(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"invoice.invoice.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"invoiceId":"%s","workorderId":"%s","status":"FINALIZED",
                            "invoiceNumber":"INV-2026-000123","total":216.53,"subtotal":200.00,"tax":16.53,
                            "taxBreakdown":[
                              {"lineItemId":"1","jurisdictionType":"STATE","jurisdictionCode":"STATE",
                               "rate":0.0725,"taxableBase":200.00,"taxAmount":14.50,"exempt":false,
                               "exemptionReasonCode":null},
                              {"lineItemId":"1","jurisdictionType":"COUNTY","jurisdictionCode":"COUNTY",
                               "rate":0.0102,"taxableBase":200.00,"taxAmount":2.03,"exempt":false,
                               "exemptionReasonCode":null}]}}
                """.formatted(eventId, INVOICE_ID, version, INVOICE_ID, WORKORDER_ID);
    }

    private String event(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"invoice.invoice.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"invoiceId":"%s","workorderId":"%s","status":"FINALIZED",
                            "invoiceNumber":"INV-2026-000123","total":216.00,"subtotal":200.00,"tax":16.00}}
                """.formatted(eventId, INVOICE_ID, version, INVOICE_ID, WORKORDER_ID);
    }

    @Test
    @DisplayName("Materializes invoice event into the replica and records the eventId")
    void upsertsReplica() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());

        listener.onInvoiceEvent(event("e-1", 5));

        ArgumentCaptor<ExtInvoice> saved = ArgumentCaptor.forClass(ExtInvoice.class);
        verify(replica).save(saved.capture());
        assertThat(saved.getValue().getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(saved.getValue().getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo("FINALIZED");
        assertThat(saved.getValue().getTotal()).isEqualByComparingTo(new BigDecimal("216.00"));
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Materializes the due-date facts into the replica (#993) and tolerates their absence")
    void projectsDueDate() {
        when(processedEvents.existsById("e-due")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        String withDueDate = """
                {"eventId":"e-due","eventType":"invoice.invoice.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":6,
                 "payload":{"invoiceId":"%s","workorderId":"%s","status":"FINALIZED",
                            "total":216.00,"dueDate":"2026-08-19","paymentTermsCode":"NET_30"}}
                """.formatted(INVOICE_ID, INVOICE_ID, WORKORDER_ID);

        listener.onInvoiceEvent(withDueDate);

        ArgumentCaptor<ExtInvoice> saved = ArgumentCaptor.forClass(ExtInvoice.class);
        verify(replica).save(saved.capture());
        assertThat(saved.getValue().getDueDate()).isEqualTo(java.time.LocalDate.parse("2026-08-19"));

        // Pre-#993 events carry no dueDate: the replica column stays null (fallback ordering).
        when(processedEvents.existsById("e-nodue")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        listener.onInvoiceEvent(event("e-nodue", 7));
        ArgumentCaptor<ExtInvoice> savedLegacy = ArgumentCaptor.forClass(ExtInvoice.class);
        verify(replica, org.mockito.Mockito.times(2)).save(savedLegacy.capture());
        assertThat(savedLegacy.getAllValues().get(1).getDueDate()).isNull();
    }

    @Test
    @DisplayName("Materializes the tax breakdown into ext_invoice_tax matching the scalar to the cent")
    void materializesTaxBreakdown() {
        when(processedEvents.existsById("e-tb")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());

        listener.onInvoiceEvent(eventWithBreakdown("e-tb", 7));

        verify(taxReplica).deleteByInvoiceId(INVOICE_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExtInvoiceTax>> saved = ArgumentCaptor.forClass(List.class);
        verify(taxReplica).saveAll(saved.capture());
        List<ExtInvoiceTax> rows = saved.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getInvoiceId()).isEqualTo(INVOICE_ID);
            assertThat(r.getAggregateVersion()).isEqualTo(7L);
        });
        // T1 invariant: the replicated per-jurisdiction amounts sum to the scalar tax (16.53).
        BigDecimal sum = rows.stream().map(ExtInvoiceTax::getTaxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("16.53"));
    }

    @Test
    @DisplayName("Leaves ext_invoice_tax untouched when the event carries no breakdown")
    void skipsTaxBreakdownWhenAbsent() {
        when(processedEvents.existsById("e-nobreak")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());

        listener.onInvoiceEvent(event("e-nobreak", 3));

        verify(taxReplica, never()).deleteByInvoiceId(any());
        verify(taxReplica, never()).saveAll(any());
    }

    @Test
    @DisplayName("Does not replicate the breakdown for a stale event")
    void skipsTaxBreakdownForStaleEvent() {
        when(processedEvents.existsById("e-stale-tb")).thenReturn(false);
        when(replica.findById(INVOICE_ID))
                .thenReturn(Optional.of(ExtInvoice.builder()
                        .invoiceId(INVOICE_ID)
                        .workorderId(WORKORDER_ID)
                        .status("POSTED")
                        .aggregateVersion(9L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));

        listener.onInvoiceEvent(eventWithBreakdown("e-stale-tb", 5));

        verify(taxReplica, never()).deleteByInvoiceId(any());
        verify(taxReplica, never()).saveAll(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onInvoiceEvent(event("e-dup", 5));

        verify(replica, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Skips stale events whose aggregateVersion is at or below the replica's")
    void skipsStaleVersions() {
        when(processedEvents.existsById("e-old")).thenReturn(false);
        when(replica.findById(INVOICE_ID))
                .thenReturn(Optional.of(ExtInvoice.builder()
                        .invoiceId(INVOICE_ID)
                        .workorderId(WORKORDER_ID)
                        .status("POSTED")
                        .aggregateVersion(9L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));

        listener.onInvoiceEvent(event("e-old", 5));

        verify(replica, never()).save(any());
        // Still recorded as processed so redelivery does not reprocess it.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips a replayed version-0 event when the replica is already newer (PR #850 review)")
    void skipsReplayedVersionZeroEvent() {
        when(processedEvents.existsById("e-zero")).thenReturn(false);
        when(replica.findById(INVOICE_ID))
                .thenReturn(Optional.of(ExtInvoice.builder()
                        .invoiceId(INVOICE_ID)
                        .workorderId(WORKORDER_ID)
                        .status("POSTED")
                        .aggregateVersion(5L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));

        listener.onInvoiceEvent(event("e-zero", 0));

        verify(replica, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Ignores other event types")
    void ignoresOtherEventTypes() {
        listener.onInvoiceEvent("""
                {"eventId":"e-2","eventType":"invoice.something.else","payload":{}}
                """);

        verify(replica, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-3")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onInvoiceEvent(event("e-3", 1)));

        verify(processedEvents, never()).save(any());
    }
}
