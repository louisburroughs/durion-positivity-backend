package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.InvoiceRegenerationRequest;
import com.positivity.accounting.internal.repository.InvoiceRegenerationRequestRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link WorkorderEventsListener} (#1537 D1): consumes {@code workorder.events.v1}
 * and resolves outstanding {@code invoice_regeneration_request} rows once a workorder fact for
 * the same workorder carries a resulting invoiceId. Same contract as {@link InvoiceEventsListener}
 * (dedupe via {@code processed_events}, transient DB errors rethrown, everything else swallowed).
 */
class WorkorderEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final InvoiceRegenerationRequestRepository requests = mock(InvoiceRegenerationRequestRepository.class);

    private WorkorderEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new WorkorderEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, requests);
    }

    private String updatedEvent(String eventId, UUID invoiceId) {
        String invoiceField = invoiceId == null ? "null" : "\"" + invoiceId + "\"";
        return """
                {"eventId":"%s","eventType":"workorder.workorder.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,
                 "payload":{"workorderId":"%s","status":"COMPLETED","invoiceId":%s}}
                """.formatted(eventId, WORKORDER_ID, WORKORDER_ID, invoiceField);
    }

    private String serviceCompletedEvent(String eventId, UUID invoiceId) {
        String invoiceField = invoiceId == null ? "null" : "\"" + invoiceId + "\"";
        return """
                {"eventId":"%s","eventType":"workorder.service.completed.v1","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,
                 "payload":{"workorderId":"%s","invoiceId":%s,"completedAt":"2026-08-27T11:00:00Z",
                            "totalAmount":100.00,"services":[]}}
                """.formatted(eventId, WORKORDER_ID, WORKORDER_ID, invoiceField);
    }

    private InvoiceRegenerationRequest pendingRequest() {
        return InvoiceRegenerationRequest.builder()
                .workorderId(WORKORDER_ID)
                .commandId(UUID.randomUUID())
                .status(InvoiceRegenerationRequest.STATUS_PENDING)
                .requestedAt(Instant.now(TEST_CLOCK))
                .build();
    }

    @Test
    @DisplayName("Resolves a pending regeneration request to COMPLETED with the resulting invoiceId")
    void resolvesPendingRequest() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        InvoiceRegenerationRequest pending = pendingRequest();
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of(pending));

        listener.onWorkorderEvent(updatedEvent("e-1", INVOICE_ID));

        ArgumentCaptor<List<InvoiceRegenerationRequest>> saved = ArgumentCaptor.forClass(List.class);
        verify(requests).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        InvoiceRegenerationRequest resolved = saved.getValue().get(0);
        assertThat(resolved.getStatus()).isEqualTo(InvoiceRegenerationRequest.STATUS_COMPLETED);
        assertThat(resolved.getResultInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(resolved.getResolvedAt()).isEqualTo(TEST_CLOCK.instant());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Resolves via WorkorderServiceCompletedV1 too")
    void resolvesPendingRequestFromServiceCompleted() {
        when(processedEvents.existsById("e-sc")).thenReturn(false);
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of(pendingRequest()));

        listener.onWorkorderEvent(serviceCompletedEvent("e-sc", INVOICE_ID));

        verify(requests).saveAll(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onWorkorderEvent(updatedEvent("e-dup", INVOICE_ID));

        verify(requests, never()).findByWorkorderIdAndStatus(any(), any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("A fact with a null invoiceId leaves the row pending")
    void nullInvoiceIdLeavesRowPending() {
        when(processedEvents.existsById("e-null")).thenReturn(false);

        listener.onWorkorderEvent(updatedEvent("e-null", null));

        verify(requests, never()).findByWorkorderIdAndStatus(any(), any());
        verify(requests, never()).saveAll(any());
        // Still recorded as processed.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("A fact for an unknown workorder is a harmless no-op still marked processed")
    void unknownWorkorderIsNoOp() {
        when(processedEvents.existsById("e-unknown")).thenReturn(false);
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of());

        listener.onWorkorderEvent(updatedEvent("e-unknown", INVOICE_ID));

        verify(requests, never()).saveAll(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Ignores unsupported event types")
    void ignoresUnsupportedEventTypes() {
        listener.onWorkorderEvent("""
                {"eventId":"e-2","eventType":"workorder.something.else","payload":{}}
                """);

        verify(requests, never()).findByWorkorderIdAndStatus(any(), any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Malformed JSON does not throw")
    void malformedJsonDoesNotThrow() {
        listener.onWorkorderEvent("not json");

        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries, and does not save ProcessedEvent")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-3")).thenReturn(false);
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onWorkorderEvent(updatedEvent("e-3", INVOICE_ID)));

        verify(processedEvents, never()).save(any());
    }
}
