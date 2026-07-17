package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer contract of the workorder.events.v1 listener (issue #925, pos-invoice
 * {@code WorkorderEventsListener} pattern): manual envelope parse, processed_events dedupe,
 * ignored types still recorded, transient DB errors rethrown for container retry/DLQ.
 */
class WorkorderEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID WORKORDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000801");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000802");
    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000803");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000804");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final AutoRegistrationService autoRegistrationService = mock(AutoRegistrationService.class);

    private WorkorderEventsListener listener;

    @BeforeEach
    void setUp() {
        listener =
                new WorkorderEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, autoRegistrationService);
    }

    private static String updatedEvent(String eventId) {
        return """
                {"eventId":"%s","eventType":"workorder.workorder.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":7,
                 "payload":{"workorderId":"%s","workorderNumber":"WO-2026-000042","status":"INVOICED",
                            "customerId":"%s","invoiceId":"%s",
                            "parts":[{"workorderLineId":"%s","productEntityId":"%s","quantity":1}],
                            "createdAt":"2026-07-01T09:00:00Z","updatedAt":"2026-07-10T23:30:00Z"}}
                """.formatted(
                        eventId, WORKORDER_ID, WORKORDER_ID, CUSTOMER_ID, INVOICE_ID, UUID.randomUUID(), PRODUCT_ID);
    }

    @Test
    @DisplayName("Delegates a workorder.workorder.updated fact to auto-registration and records the eventId")
    void delegatesToAutoRegistration() {
        when(processedEvents.existsById("e-1")).thenReturn(false);

        listener.onWorkorderEvent(updatedEvent("e-1"));

        ArgumentCaptor<WorkorderUpdatedV1> captor = ArgumentCaptor.captor();
        verify(autoRegistrationService).registerFromWorkorderSale(captor.capture());
        WorkorderUpdatedV1 payload = captor.getValue();
        assertThat(payload.workorderId()).isEqualTo(WORKORDER_ID);
        assertThat(payload.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(payload.invoiceId()).isEqualTo(INVOICE_ID);
        assertThat(payload.parts()).hasSize(1);
        assertThat(payload.parts().getFirst().productEntityId()).isEqualTo(PRODUCT_ID);
        assertThat(payload.updatedAt()).isEqualTo(Instant.parse("2026-07-10T23:30:00Z"));

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.captor();
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("e-1");
        assertThat(processed.getValue().getOwner()).isEqualTo(WorkorderEventsListener.OWNER);
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onWorkorderEvent(updatedEvent("e-dup"));

        verify(autoRegistrationService, never()).registerFromWorkorderSale(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Ignores other event types but still records them processed")
    void ignoresOtherEventTypesButRecordsThem() {
        when(processedEvents.existsById("e-2")).thenReturn(false);

        listener.onWorkorderEvent("""
                {"eventId":"e-2","eventType":"workorder.jobtime.recorded","payload":{}}
                """);

        verify(autoRegistrationService, never()).registerFromWorkorderSale(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips unparsable messages without recording anything")
    void skipsUnparsableMessages() {
        listener.onWorkorderEvent("this is not json");

        verify(autoRegistrationService, never()).registerFromWorkorderSale(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Skips events without an eventId")
    void skipsMissingEventId() {
        listener.onWorkorderEvent("""
                {"eventType":"workorder.workorder.updated","payload":{}}
                """);

        verify(autoRegistrationService, never()).registerFromWorkorderSale(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Records a malformed payload as processed instead of poisoning the partition")
    void malformedPayloadIsSwallowedButRecorded() {
        when(processedEvents.existsById("e-3")).thenReturn(false);

        listener.onWorkorderEvent("""
                {"eventId":"e-3","eventType":"workorder.workorder.updated",
                 "payload":{"workorderId":"not-a-uuid"}}
                """);

        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        when(autoRegistrationService.registerFromWorkorderSale(any())).thenThrow(new QueryTimeoutException("db"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onWorkorderEvent(updatedEvent("e-4")));

        verify(processedEvents, never()).save(any());
    }
}
