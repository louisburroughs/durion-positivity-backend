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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
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
        listener = new InvoiceEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEvents,
                replica,
                taxReplica,
                org.mockito.Mockito.mock(ObjectProvider.class));
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
        verify(replica).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(saved.getValue().getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo("FINALIZED");
        assertThat(saved.getValue().getTotal()).isEqualByComparingTo(new BigDecimal("216.00"));
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Stamps the processed-event row with the invoice owner tag (#1537 D2)")
    void stampsOwnerOnProcessedEvent() {
        when(processedEvents.existsById("e-owner")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());

        listener.onInvoiceEvent(event("e-owner", 5));

        ArgumentCaptor<com.positivity.accounting.internal.entity.ProcessedEvent> saved =
                ArgumentCaptor.forClass(com.positivity.accounting.internal.entity.ProcessedEvent.class);
        verify(processedEvents).save(saved.capture());
        assertThat(saved.getValue().getOwner()).isEqualTo("invoice");
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
        verify(replica).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDueDate()).isEqualTo(java.time.LocalDate.parse("2026-08-19"));

        // Pre-#993 events carry no dueDate: the replica column stays null (fallback ordering).
        when(processedEvents.existsById("e-nodue")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        listener.onInvoiceEvent(event("e-nodue", 7));
        ArgumentCaptor<ExtInvoice> savedLegacy = ArgumentCaptor.forClass(ExtInvoice.class);
        verify(replica, org.mockito.Mockito.times(2)).saveAndFlush(savedLegacy.capture());
        assertThat(savedLegacy.getAllValues().get(1).getDueDate()).isNull();
    }

    @Test
    @DisplayName("Materializes the deposit-take marker into the replica (#1623) and tolerates its absence")
    void projectsDepositSourceType() {
        when(processedEvents.existsById("e-dep")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        String depositTake = """
                {"eventId":"e-dep","eventType":"invoice.invoice.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":4,
                 "payload":{"invoiceId":"%s","workorderId":"%s","status":"FINALIZED",
                            "total":108.00,"depositSourceType":"WORKORDER",
                            "depositSourceId":"00000000-0000-0000-0000-0000000000e1"}}
                """.formatted(INVOICE_ID, INVOICE_ID, WORKORDER_ID);

        listener.onInvoiceEvent(depositTake);

        ArgumentCaptor<ExtInvoice> saved = ArgumentCaptor.forClass(ExtInvoice.class);
        verify(replica).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDepositSourceType()).isEqualTo("WORKORDER");

        // Pre-#1623 events carry no marker: the replica column stays null (ordinary invoice).
        when(processedEvents.existsById("e-nodep")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        listener.onInvoiceEvent(event("e-nodep", 5));
        ArgumentCaptor<ExtInvoice> savedLegacy = ArgumentCaptor.forClass(ExtInvoice.class);
        verify(replica, org.mockito.Mockito.times(2)).saveAndFlush(savedLegacy.capture());
        assertThat(savedLegacy.getAllValues().get(1).getDepositSourceType()).isNull();
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

        verify(replica, never()).saveAndFlush(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Skips stale events whose aggregateVersion is strictly below the replica's")
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

        verify(replica, never()).saveAndFlush(any());
        // Still recorded as processed so redelivery does not reprocess it.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Applies an event with an equal version stamp (#1486, ReplicaVersionGuard)")
    void appliesEqualVersion() {
        // Load-bearing: aggregateVersion strictly advances (committed JPA @Version, flushed before
        // emit), so an equal version means identical content, and POST .../facts/replay
        // deliberately resends a fact at the held version to repair a replica with wrong or
        // missing rows. Skipping on equal (the old `>=` guard) silently turned replay into a no-op
        // (#1486).
        when(processedEvents.existsById("e-eq")).thenReturn(false);
        when(replica.findById(INVOICE_ID))
                .thenReturn(Optional.of(ExtInvoice.builder()
                        .invoiceId(INVOICE_ID)
                        .workorderId(WORKORDER_ID)
                        .status("POSTED")
                        .aggregateVersion(9L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));

        listener.onInvoiceEvent(event("e-eq", 9));

        ArgumentCaptor<ExtInvoice> saved = ArgumentCaptor.forClass(ExtInvoice.class);
        verify(replica).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(9L);
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

        verify(replica, never()).saveAndFlush(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Records other event types in processed_events without touching the replica (#1537 F1)")
    void ignoresOtherEventTypes() {
        // pos-invoice's ManifestPublisher counts every fact on invoice.events.v1 in the window
        // regardless of type (e.g. invoice.billing-rules.updated shares the topic). If an ignored
        // type's eventId never reaches processed_events, InvoiceManifestListener's window count can
        // never agree with the manifest and drift-repair loops forever (#1537 F1).
        when(processedEvents.existsById("e-2")).thenReturn(false);

        listener.onInvoiceEvent("""
                {"eventId":"e-2","eventType":"invoice.something.else","payload":{}}
                """);

        verify(replica, never()).saveAndFlush(any());
        ArgumentCaptor<com.positivity.accounting.internal.entity.ProcessedEvent> saved =
                ArgumentCaptor.forClass(com.positivity.accounting.internal.entity.ProcessedEvent.class);
        verify(processedEvents).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isEqualTo("e-2");
        assertThat(saved.getValue().getOwner()).isEqualTo("invoice");
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

    @Test
    @DisplayName(
            "Materializes an invoice with no originating workorder (#1651) into the replica with a null workorderId")
    void materializesNullWorkorderId() {
        when(processedEvents.existsById("e-noworkorder")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        String noWorkorder = """
                {"eventId":"e-noworkorder","eventType":"invoice.invoice.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,
                 "payload":{"invoiceId":"%s","workorderId":null,"status":"FINALIZED",
                            "invoiceNumber":"INV-2026-000200","total":150.00,"subtotal":140.00,"tax":10.00}}
                """.formatted(INVOICE_ID, INVOICE_ID);

        listener.onInvoiceEvent(noWorkorder);

        ArgumentCaptor<ExtInvoice> saved = ArgumentCaptor.forClass(ExtInvoice.class);
        verify(replica).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(saved.getValue().getWorkorderId()).isNull();
        assertThat(saved.getValue().getStatus()).isEqualTo("FINALIZED");
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName(
            "A replica persist failure (e.g. a DB constraint violation) is counted and rethrown for container retry/DLQ")
    void replicaPersistFailureIsCountedAndRethrown() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        InvoiceEventsListener listenerWithMetrics = new InvoiceEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEvents, replica, taxReplica, provider);

        when(processedEvents.existsById("e-persist-fail")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        when(replica.saveAndFlush(any(ExtInvoice.class)))
                .thenThrow(new DataIntegrityViolationException("null value in column \"party_id\""));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> listenerWithMetrics.onInvoiceEvent(event("e-persist-fail", 1)));

        var counter = meterRegistry.find("replica.persist.failed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1d);
        // Not marked processed — same retry/DLQ path as a transient DB error (ADR-0044 §4): a
        // replica row the DB refuses must not be silently accepted as "handled".
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("A non-transient DataAccessException that is not a constraint/integrity violation (e.g. a programming"
            + " error) is not counted as a replica persist failure and follows the generic path")
    void nonIntegrityViolationDataAccessExceptionIsNotCountedAsPersistFailure() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        InvoiceEventsListener listenerWithMetrics = new InvoiceEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEvents, replica, taxReplica, provider);

        when(processedEvents.existsById("e-programming-error")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        when(replica.saveAndFlush(any(ExtInvoice.class))).thenThrow(new InvalidDataAccessApiUsageException("boom"));

        // Does not propagate: falls into the generic catch (Exception) path below, same as any
        // other malformed/unexpected error, and is logged/swallowed rather than rethrown.
        listenerWithMetrics.onInvoiceEvent(event("e-programming-error", 1));

        var counter = meterRegistry.find("replica.persist.failed").counter();
        assertThat(counter == null ? 0d : counter.count()).isEqualTo(0d);
        // Generic path still records the event as processed, same as any other swallowed error.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("A constraint/integrity violation from the tax replica (ext_invoice_tax) is also counted and rethrown")
    void taxReplicaIntegrityViolationIsCountedAndRethrown() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        InvoiceEventsListener listenerWithMetrics = new InvoiceEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEvents, replica, taxReplica, provider);

        when(processedEvents.existsById("e-tax-persist-fail")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        when(taxReplica.saveAll(any()))
                .thenThrow(new DataIntegrityViolationException("value too long for type character varying"));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> listenerWithMetrics.onInvoiceEvent(eventWithBreakdown("e-tax-persist-fail", 1)));

        var counter = meterRegistry.find("replica.persist.failed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1d);
        // Not marked processed — same retry/DLQ path as ext_invoice constraint rejections above.
        verify(processedEvents, never()).save(any());
    }
}
