package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.ExtBillingRulesReplica;
import com.positivity.workorder.internal.entity.ExtInvoiceReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.repository.ExtBillingRulesReplicaRepository;
import com.positivity.workorder.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.service.InvoiceEventsListener;
import com.positivity.workorder.internal.service.WorkorderFactPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class InvoiceEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtInvoiceReplicaRepository replica = mock(ExtInvoiceReplicaRepository.class);
    private final ExtBillingRulesReplicaRepository billingRules = mock(ExtBillingRulesReplicaRepository.class);
    private final WorkorderRepository workorders = mock(WorkorderRepository.class);
    private final WorkorderFactPublisher factPublisher = mock(WorkorderFactPublisher.class);

    private InvoiceEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new InvoiceEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEvents, replica, billingRules, workorders, factPublisher);
    }

    private String event(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"invoice.invoice.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"invoiceId":"%s","workorderId":"%s","status":"DRAFT",
                            "invoiceNumber":"INV-2026-000123","total":216.00,"subtotal":200.00,"tax":16.00}}
                """.formatted(eventId, INVOICE_ID, version, INVOICE_ID, WORKORDER_ID);
    }

    @Test
    @DisplayName("Materializes invoice fact into ext_invoice and records the eventId")
    void upsertsReplica() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        when(workorders.findById(WORKORDER_ID)).thenReturn(Optional.empty());

        listener.onInvoiceEvent(event("e-1", 5));

        ArgumentCaptor<ExtInvoiceReplica> saved = ArgumentCaptor.forClass(ExtInvoiceReplica.class);
        verify(replica).save(saved.capture());
        assertThat(saved.getValue().getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(saved.getValue().getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getValue().getTotal()).isEqualByComparingTo(new BigDecimal("216.00"));
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("First fact for a workorder resolves the pending state: links invoiceId and republishes the fact")
    void linksPendingWorkorder() {
        when(processedEvents.existsById("e-link")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        when(workorders.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        listener.onInvoiceEvent(event("e-link", 1));

        assertThat(workorder.getInvoiceId()).isEqualTo(INVOICE_ID);
        verify(workorders).save(workorder);
        verify(factPublisher).markChanged(WORKORDER_ID);
    }

    @Test
    @DisplayName("Already-linked workorder is left untouched")
    void doesNotRelinkWorkorder() {
        UUID otherInvoiceId = UUID.fromString("00000000-0000-0000-0000-00000000000f");
        when(processedEvents.existsById("e-again")).thenReturn(false);
        when(replica.findById(INVOICE_ID)).thenReturn(Optional.empty());
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setInvoiceId(otherInvoiceId);
        when(workorders.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        listener.onInvoiceEvent(event("e-again", 2));

        assertThat(workorder.getInvoiceId()).isEqualTo(otherInvoiceId);
        verify(workorders, never()).save(any());
        verify(factPublisher, never()).markChanged(any());
    }

    @Test
    @DisplayName("Materializes billing-rules fact into ext_billing_rules (#902)")
    void upsertsBillingRulesReplica() {
        when(processedEvents.existsById("e-br")).thenReturn(false);
        when(billingRules.findById("party-001")).thenReturn(Optional.empty());

        listener.onInvoiceEvent("""
                {"eventId":"e-br","eventType":"invoice.billing-rules.updated","schemaVersion":1,
                 "aggregateId":"00000000-0000-0000-0000-00000000000b","aggregateVersion":3,
                 "payload":{"partyId":"party-001","purchaseOrderRequired":true,
                            "paymentTermsCode":"NET_30","invoiceDeliveryMethod":"EMAIL",
                            "invoiceGroupingStrategy":"PER_WORKORDER"}}
                """);

        ArgumentCaptor<ExtBillingRulesReplica> saved = ArgumentCaptor.forClass(ExtBillingRulesReplica.class);
        verify(billingRules).save(saved.capture());
        assertThat(saved.getValue().getPartyId()).isEqualTo("party-001");
        assertThat(saved.getValue().isPurchaseOrderRequired()).isTrue();
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(3L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips stale billing-rules facts below the replica's version")
    void skipsStaleBillingRules() {
        when(processedEvents.existsById("e-br-old")).thenReturn(false);
        when(billingRules.findById("party-001"))
                .thenReturn(Optional.of(ExtBillingRulesReplica.builder()
                        .partyId("party-001")
                        .purchaseOrderRequired(true)
                        .aggregateVersion(9L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));

        listener.onInvoiceEvent("""
                {"eventId":"e-br-old","eventType":"invoice.billing-rules.updated","schemaVersion":1,
                 "aggregateId":"00000000-0000-0000-0000-00000000000b","aggregateVersion":2,
                 "payload":{"partyId":"party-001","purchaseOrderRequired":false}}
                """);

        verify(billingRules, never()).save(any());
        verify(processedEvents).save(any());
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
    @DisplayName("Skips stale facts whose aggregateVersion is below the replica's")
    void skipsStaleVersions() {
        when(processedEvents.existsById("e-old")).thenReturn(false);
        when(replica.findById(INVOICE_ID))
                .thenReturn(Optional.of(ExtInvoiceReplica.builder()
                        .invoiceId(INVOICE_ID)
                        .workorderId(WORKORDER_ID)
                        .status("FINALIZED")
                        .aggregateVersion(9L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));

        listener.onInvoiceEvent(event("e-old", 5));

        verify(replica, never()).save(any());
        // Still recorded as processed so the owner's manifest reconciles.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Ignored event types still record their eventId for manifest reconciliation")
    void ignoredTypesStillRecordEventId() {
        when(processedEvents.existsById("e-other")).thenReturn(false);

        listener.onInvoiceEvent("""
                {"eventId":"e-other","eventType":"invoice.something.else","payload":{}}
                """);

        verify(replica, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Events without an eventId are skipped entirely")
    void skipsMissingEventId() {
        listener.onInvoiceEvent("""
                {"eventType":"invoice.invoice.updated","payload":{}}
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
