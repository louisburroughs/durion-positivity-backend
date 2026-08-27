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
 * Unit tests for {@link WorkorderEventsListener} (#1537 D1, extended F4): consumes {@code
 * workorder.events.v1} and resolves outstanding {@code invoice_regeneration_request} rows once a
 * workorder fact for the same workorder carries a resulting invoiceId — but only when the fact
 * post-dates the request and carries an invoiceId other than the one the requester already had
 * (see the class javadoc on {@link WorkorderEventsListener} for why both conditions are required).
 * Same contract as {@link InvoiceEventsListener} (dedupe via {@code processed_events}, transient
 * DB errors rethrown, everything else swallowed).
 */
class WorkorderEventsListenerTest {
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-27T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(REQUESTED_AT, ZoneOffset.UTC);
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID PRIOR_INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final InvoiceRegenerationRequestRepository requests = mock(InvoiceRegenerationRequestRepository.class);

    private WorkorderEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new WorkorderEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, requests);
    }

    private String updatedEvent(String eventId, UUID invoiceId, String updatedAt) {
        String invoiceField = invoiceId == null ? "null" : "\"" + invoiceId + "\"";
        String updatedAtField = updatedAt == null ? "null" : "\"" + updatedAt + "\"";
        return """
                {"eventId":"%s","eventType":"workorder.workorder.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,
                 "payload":{"workorderId":"%s","status":"COMPLETED","invoiceId":%s,"updatedAt":%s}}
                """.formatted(eventId, WORKORDER_ID, WORKORDER_ID, invoiceField, updatedAtField);
    }

    private String serviceCompletedEvent(String eventId, UUID invoiceId, String completedAt) {
        String invoiceField = invoiceId == null ? "null" : "\"" + invoiceId + "\"";
        return """
                {"eventId":"%s","eventType":"workorder.service.completed.v1","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,
                 "payload":{"workorderId":"%s","invoiceId":%s,"completedAt":"%s",
                            "totalAmount":100.00,"services":[]}}
                """.formatted(eventId, WORKORDER_ID, WORKORDER_ID, invoiceField, completedAt);
    }

    private InvoiceRegenerationRequest pendingRequest() {
        return pendingRequest(null);
    }

    private InvoiceRegenerationRequest pendingRequest(UUID priorInvoiceId) {
        return InvoiceRegenerationRequest.builder()
                .workorderId(WORKORDER_ID)
                .commandId(UUID.randomUUID())
                .status(InvoiceRegenerationRequest.STATUS_PENDING)
                .requestedAt(REQUESTED_AT)
                .priorInvoiceId(priorInvoiceId)
                .build();
    }

    @Test
    @DisplayName("Resolves a pending regeneration request to COMPLETED with the resulting invoiceId")
    void resolvesPendingRequest() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        InvoiceRegenerationRequest pending = pendingRequest();
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of(pending));

        listener.onWorkorderEvent(updatedEvent("e-1", INVOICE_ID, "2026-08-27T12:00:05Z"));

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

        listener.onWorkorderEvent(serviceCompletedEvent("e-sc", INVOICE_ID, "2026-08-27T12:00:05Z"));

        verify(requests).saveAll(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onWorkorderEvent(updatedEvent("e-dup", INVOICE_ID, "2026-08-27T12:00:05Z"));

        verify(requests, never()).findByWorkorderIdAndStatus(any(), any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("A fact with a null invoiceId leaves the row pending")
    void nullInvoiceIdLeavesRowPending() {
        when(processedEvents.existsById("e-null")).thenReturn(false);

        listener.onWorkorderEvent(updatedEvent("e-null", null, "2026-08-27T12:00:05Z"));

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

        listener.onWorkorderEvent(updatedEvent("e-unknown", INVOICE_ID, "2026-08-27T12:00:05Z"));

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
                .isThrownBy(() -> listener.onWorkorderEvent(updatedEvent("e-3", INVOICE_ID, "2026-08-27T12:00:05Z")));

        verify(processedEvents, never()).save(any());
    }

    // ---- #1537 F4: an unrelated update must not falsely resolve a request with a stale invoiceId ----

    @Test
    @DisplayName("F4: a fact carrying the SAME invoiceId the requester already had does not resolve the request"
            + " (the false-resolution scenario)")
    void factEchoingPriorInvoiceIdDoesNotResolve() {
        // Workorder W already has invoice I1 (why regeneration was requested). A technician then
        // edits an unrelated field, emitting WorkorderUpdatedV1{invoiceId: I1} — the same id, not
        // evidence regeneration produced anything.
        when(processedEvents.existsById("e-echo")).thenReturn(false);
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of(pendingRequest(PRIOR_INVOICE_ID)));

        listener.onWorkorderEvent(updatedEvent("e-echo", PRIOR_INVOICE_ID, "2026-08-27T12:00:05Z"));

        verify(requests, never()).saveAll(any());
        // Still recorded as processed — this is a well-formed fact, just not a resolving one.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("F4: a fact whose timestamp predates the request does not resolve it (stale/reordered delivery)")
    void staleFactDoesNotResolve() {
        when(processedEvents.existsById("e-stale")).thenReturn(false);
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of(pendingRequest()));

        // updatedAt is BEFORE requestedAt (12:00:00Z) — this snapshot predates the command.
        listener.onWorkorderEvent(updatedEvent("e-stale", INVOICE_ID, "2026-08-27T11:59:00Z"));

        verify(requests, never()).saveAll(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("F4: a fact with no verifiable timestamp (null updatedAt) does not resolve the request")
    void unverifiableTimestampDoesNotResolve() {
        when(processedEvents.existsById("e-notime")).thenReturn(false);
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of(pendingRequest()));

        listener.onWorkorderEvent(updatedEvent("e-notime", INVOICE_ID, null));

        verify(requests, never()).saveAll(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("F4: a genuinely new invoiceId that post-dates the request still resolves it")
    void newInvoiceIdPostDatingRequestResolves() {
        when(processedEvents.existsById("e-new")).thenReturn(false);
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of(pendingRequest(PRIOR_INVOICE_ID)));

        listener.onWorkorderEvent(updatedEvent("e-new", INVOICE_ID, "2026-08-27T12:00:05Z"));

        ArgumentCaptor<List<InvoiceRegenerationRequest>> saved = ArgumentCaptor.forClass(List.class);
        verify(requests).saveAll(saved.capture());
        assertThat(saved.getValue().get(0).getResultInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(saved.getValue().get(0).getStatus()).isEqualTo(InvoiceRegenerationRequest.STATUS_COMPLETED);
    }

    @Test
    @DisplayName("F4: with no priorInvoiceId (first-ever invoice), any post-dating non-null invoiceId resolves")
    void noPriorInvoiceIdResolvesOnFirstFact() {
        when(processedEvents.existsById("e-first")).thenReturn(false);
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of(pendingRequest(null)));

        listener.onWorkorderEvent(updatedEvent("e-first", INVOICE_ID, "2026-08-27T12:00:05Z"));

        verify(requests).saveAll(any());
    }

    @Test
    @DisplayName("F4: WorkorderServiceCompletedV1 is also subject to the post-dates-and-differs guard")
    void serviceCompletedEchoingPriorInvoiceIdDoesNotResolve() {
        when(processedEvents.existsById("e-sc-echo")).thenReturn(false);
        when(requests.findByWorkorderIdAndStatus(WORKORDER_ID, InvoiceRegenerationRequest.STATUS_PENDING))
                .thenReturn(List.of(pendingRequest(PRIOR_INVOICE_ID)));

        listener.onWorkorderEvent(serviceCompletedEvent("e-sc-echo", PRIOR_INVOICE_ID, "2026-08-27T12:00:05Z"));

        verify(requests, never()).saveAll(any());
        verify(processedEvents).save(any());
    }

    // ---- #1537 F4: reaping requests that never get a resolving fact ----

    @Test
    @DisplayName("F4: reapExpiredRequests marks PENDING rows older than the TTL FAILED")
    void reapMarksExpiredPendingRequestsFailed() {
        InvoiceRegenerationRequest stuck = pendingRequest();
        when(requests.findByStatusAndRequestedAtBefore(
                        org.mockito.ArgumentMatchers.eq(InvoiceRegenerationRequest.STATUS_PENDING), any()))
                .thenReturn(List.of(stuck));

        listener.reapExpiredRequests();

        ArgumentCaptor<List<InvoiceRegenerationRequest>> saved = ArgumentCaptor.forClass(List.class);
        verify(requests).saveAll(saved.capture());
        InvoiceRegenerationRequest reaped = saved.getValue().get(0);
        assertThat(reaped.getStatus()).isEqualTo(InvoiceRegenerationRequest.STATUS_FAILED);
        assertThat(reaped.getResolvedAt()).isEqualTo(TEST_CLOCK.instant());
    }

    @Test
    @DisplayName("F4: reapExpiredRequests is a no-op when nothing has expired")
    void reapIsNoOpWhenNothingExpired() {
        when(requests.findByStatusAndRequestedAtBefore(
                        org.mockito.ArgumentMatchers.eq(InvoiceRegenerationRequest.STATUS_PENDING), any()))
                .thenReturn(List.of());

        listener.reapExpiredRequests();

        verify(requests, never()).saveAll(any());
    }
}
