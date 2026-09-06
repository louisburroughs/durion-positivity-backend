package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.accounting.InvoiceGlPostedV1;
import com.positivity.invoice.internal.client.TaxLifecycleClient;
import com.positivity.invoice.internal.config.InvoiceEventPublisher;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link AccountingEventsListener} closes the GL-posting loop (#1843): pos-accounting's
 * {@code accounting.invoice.gl-posted} fact is the only thing that moves an invoice
 * {@code FINALIZED -> POSTED}. The listener is wired over the real
 * {@link InvoiceFinalizationServiceImpl} so the state transition is exercised end to end, with
 * the same consumer contract as the replica listeners (dedup row per fact, transient errors
 * rethrown, malformed payloads dropped but recorded).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccountingEventsListener — accounting.invoice.gl-posted")
class AccountingEventsListenerTest {

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000a0a0");
    private static final UUID OTHER_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000b0b0");
    private static final Instant FINALIZED_AT = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:05Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceEventPublisher invoiceEventPublisher;

    @Mock
    private ElevationTokenService elevationTokenService;

    @Mock
    private TaxLifecycleClient taxLifecycleClient;

    @Mock
    private InvoiceDueDateService invoiceDueDateService;

    @Mock
    private InvoiceTaxCalculator invoiceTaxCalculator;

    @Mock
    private InvoiceTaxBreakdownWriter taxBreakdownWriter;

    private AccountingEventsListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ObjectProvider<MeterRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(meterRegistry);
        InvoiceFinalizationService finalizationService = new InvoiceFinalizationServiceImpl(
                invoiceRepository,
                clock,
                elevationTokenService,
                invoiceEventPublisher,
                taxLifecycleClient,
                invoiceDueDateService,
                invoiceTaxCalculator,
                taxBreakdownWriter);
        listener = new AccountingEventsListener(
                clock, objectMapper, processedEventRepository, finalizationService, registryProvider);
    }

    private static Invoice invoice(InvoiceStatus status) {
        Invoice invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setStatus(status);
        invoice.setFinalizedAt(FINALIZED_AT);
        return invoice;
    }

    private static String glPosted(String eventId, String invoiceId, String postingKind, Instant finalizedAt) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":1,
                 "occurredAtUtc":"2026-08-11T09:00:03Z","sourceService":"pos-accounting",
                 "payload":{"invoiceId":"%s","journalEntryId":"%s","postingKind":"%s",
                   "finalizedAt":"%s","postedAt":"2026-08-11T09:00:03Z",
                   "reversedJournalEntryId":%s}}""".formatted(
                        eventId,
                        InvoiceGlPostedV1.EVENT_TYPE,
                        invoiceId,
                        invoiceId,
                        JOURNAL_ENTRY_ID,
                        postingKind,
                        finalizedAt,
                        "REVERSED".equals(postingKind) ? "\"" + OTHER_ENTRY_ID + "\"" : "null");
    }

    private ProcessedEvent recordedEvent() {
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("POSTED fact moves a FINALIZED invoice to POSTED, records glEntryId, and re-publishes it")
    void postedFactTransitionsFinalizedInvoice() {
        Invoice invoice = invoice(InvoiceStatus.FINALIZED);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

        listener.onAccountingEvent(glPosted("evt-1", INVOICE_ID.toString(), "POSTED", FINALIZED_AT));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.POSTED);
        assertThat(invoice.getGlEntryId()).isEqualTo(JOURNAL_ENTRY_ID);
        verify(invoiceRepository).save(invoice);
        verify(invoiceEventPublisher).publishInvoiceUpdated(invoice);
        ProcessedEvent recorded = recordedEvent();
        assertThat(recorded.getEventId()).isEqualTo("evt-1");
        assertThat(recorded.getOwner()).isEqualTo(AccountingEventsListener.OWNER);
        assertThat(recorded.getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a redelivered fact for an invoice already POSTED under the same entry is a no-op")
    void duplicatePostedFactIsSkipped() {
        Invoice invoice = invoice(InvoiceStatus.POSTED);
        invoice.setGlEntryId(JOURNAL_ENTRY_ID);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

        listener.onAccountingEvent(glPosted("evt-2", INVOICE_ID.toString(), "POSTED", FINALIZED_AT));

        verify(invoiceRepository, never()).save(any());
        verify(invoiceEventPublisher, never()).publishInvoiceUpdated(any());
        assertThat(recordedEvent().getEventId()).isEqualTo("evt-2");
    }

    @Test
    @DisplayName("a fact for an invoice reverted to DRAFT in the race window is skipped, not applied")
    void draftInvoiceIsSkipped() {
        Invoice invoice = invoice(InvoiceStatus.DRAFT);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

        listener.onAccountingEvent(glPosted("evt-3", INVOICE_ID.toString(), "POSTED", FINALIZED_AT));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.getGlEntryId()).isNull();
        verify(invoiceRepository, never()).save(any());
        verify(invoiceEventPublisher, never()).publishInvoiceUpdated(any());
        assertThat(recordedEvent().getEventId()).isEqualTo("evt-3");
    }

    @Test
    @DisplayName("a fact for an unknown invoice is skipped and still recorded")
    void unknownInvoiceIsSkipped() {
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.empty());

        listener.onAccountingEvent(glPosted("evt-4", INVOICE_ID.toString(), "POSTED", FINALIZED_AT));

        verify(invoiceRepository, never()).save(any());
        verify(invoiceEventPublisher, never()).publishInvoiceUpdated(any());
        assertThat(recordedEvent().getEventId()).isEqualTo("evt-4");
    }

    @Test
    @DisplayName("REVERSED fact changes nothing in pos-invoice but is recorded")
    void reversedFactIsNoOp() {
        listener.onAccountingEvent(glPosted("evt-5", INVOICE_ID.toString(), "REVERSED", FINALIZED_AT));

        verify(invoiceRepository, never()).findById(any());
        verify(invoiceRepository, never()).save(any());
        verify(invoiceEventPublisher, never()).publishInvoiceUpdated(any());
        assertThat(recordedEvent().getEventId()).isEqualTo("evt-5");
    }

    @Test
    @DisplayName("an accounting fact of another type is ignored but recorded, keeping the dedup log per fact")
    void unknownEventTypeIsIgnoredAndRecorded() {
        listener.onAccountingEvent("""
                {"eventId":"evt-6","eventType":"accounting.period.closed","payload":{"periodId":"2026-08"}}""");

        verify(invoiceRepository, never()).findById(any());
        verify(invoiceRepository, never()).save(any());
        assertThat(recordedEvent().getEventId()).isEqualTo("evt-6");
    }

    @Test
    @DisplayName("a malformed payload is dropped, counted, and recorded so the partition is not wedged")
    void malformedPayloadIsDroppedAndRecorded() {
        listener.onAccountingEvent(glPosted("evt-7", "not-a-uuid", "POSTED", FINALIZED_AT));

        verify(invoiceRepository, never()).findById(any());
        verify(invoiceRepository, never()).save(any());
        assertThat(recordedEvent().getEventId()).isEqualTo("evt-7");
        assertThat(meterRegistry
                        .get("replica.payload.rejected")
                        .tag("owner", AccountingEventsListener.OWNER)
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a transient database error is rethrown so the container retries instead of losing the fact")
    void transientDatabaseErrorIsRethrown() {
        doThrow(new QueryTimeoutException("lock wait")).when(invoiceRepository).findById(any());

        assertThatThrownBy(() ->
                        listener.onAccountingEvent(glPosted("evt-8", INVOICE_ID.toString(), "POSTED", FINALIZED_AT)))
                .isInstanceOf(QueryTimeoutException.class);

        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a replayed eventId is a no-op")
    void replayedEventIdIsIgnored() {
        when(processedEventRepository.existsById("evt-9")).thenReturn(true);

        listener.onAccountingEvent(glPosted("evt-9", INVOICE_ID.toString(), "POSTED", FINALIZED_AT));

        verify(invoiceRepository, never()).findById(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a message that is not JSON, or has no eventId, is skipped without a dedup row")
    void unparsableOrAnonymousMessageIsSkipped() {
        listener.onAccountingEvent("{not json");
        listener.onAccountingEvent(glPosted("", INVOICE_ID.toString(), "POSTED", FINALIZED_AT));

        verify(invoiceRepository, never()).findById(any());
        verify(processedEventRepository, never()).save(any());
    }
}
