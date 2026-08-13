package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.entity.ServiceHistory;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link WorkorderEventsListener} (FI-3, #1133): completion facts project into
 * {@code service_history}, declined facts raise exactly one {@code DECLINED_SERVICE_FOLLOWUP}
 * task, both idempotent, and unrelated workorder fact types are ignored.
 */
class WorkorderEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();
    private static final UUID WORKORDER_ID = UUID.fromString("01980a58-0000-7000-8000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-000000000002");
    private static final UUID VEHICLE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000003");
    private static final UUID LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000004");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ServiceHistoryRepository serviceHistory = mock(ServiceHistoryRepository.class);
    private final FollowUpTaskRepository followUps = mock(FollowUpTaskRepository.class);

    private WorkorderEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new WorkorderEventsListener(TEST_CLOCK, MAPPER, processedEvents, serviceHistory, followUps);
    }

    private String completedEvent(String eventId) {
        return """
                {"eventId":"%s","eventType":"workorder.service.completed.v1","sourceService":"pos-workorder",
                 "payload":{"workorderId":"%s","workorderNumber":"WO-2026-1001","partyId":"%s","vehicleId":"%s",
                            "completedAt":"2026-07-20T10:00:00Z","totalAmount":149.99,
                            "services":[{"workorderLineId":"%s","description":"Oil change","lineTotal":149.99}]}}
                """.formatted(eventId, WORKORDER_ID, PARTY_ID, VEHICLE_ID, LINE_ID);
    }

    private String declinedEvent(String eventId) {
        return """
                {"eventId":"%s","eventType":"workorder.service-line.declined.v1","sourceService":"pos-workorder",
                 "payload":{"workorderId":"%s","workorderNumber":"WO-2026-1001","workorderLineId":"%s",
                            "partyId":"%s","vehicleId":"%s","description":"Brake pads",
                            "declineReason":"Defer to next visit","declinedAt":"2026-07-20T10:00:00Z"}}
                """.formatted(eventId, WORKORDER_ID, LINE_ID, PARTY_ID, VEHICLE_ID);
    }

    @Test
    @DisplayName("Completion fact projects a service_history row and records the eventId")
    void projectsServiceHistory() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(serviceHistory.existsBySourceEventId("e-1")).thenReturn(false);

        listener.onWorkorderEvent(completedEvent("e-1"));

        ArgumentCaptor<ServiceHistory> saved = ArgumentCaptor.forClass(ServiceHistory.class);
        verify(serviceHistory).save(saved.capture());
        assertThat(saved.getValue().getPartyId()).isEqualTo(PARTY_ID);
        assertThat(saved.getValue().getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(saved.getValue().getSourceWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("149.99");
        assertThat(saved.getValue().getSourceEventId()).isEqualTo("e-1");
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Declined fact raises exactly one DECLINED_SERVICE_FOLLOWUP task")
    void raisesDeclinedFollowUp() {
        when(processedEvents.existsById("e-2")).thenReturn(false);
        when(followUps.existsBySourceEventId("e-2")).thenReturn(false);

        listener.onWorkorderEvent(declinedEvent("e-2"));

        ArgumentCaptor<FollowUpTask> saved = ArgumentCaptor.forClass(FollowUpTask.class);
        verify(followUps).save(saved.capture());
        FollowUpTask task = saved.getValue();
        assertThat(task.getType()).isEqualTo(FollowUpType.DECLINED_SERVICE_FOLLOWUP);
        assertThat(task.getStatus()).isEqualTo(FollowUpStatus.OPEN);
        assertThat(task.getPartyId()).isEqualTo(PARTY_ID);
        assertThat(task.getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(task.getSourceWorkorderId()).isEqualTo(WORKORDER_ID.toString());
        assertThat(task.getSourceEventId()).isEqualTo("e-2");
        assertThat(task.getReason()).isEqualTo("Brake pads");
        assertThat(task.getNotes()).isEqualTo("Defer to next visit");
    }

    @Test
    @DisplayName("Duplicate envelope is skipped via processed_events")
    void skipsDuplicate() {
        when(processedEvents.existsById("e-1")).thenReturn(true);

        listener.onWorkorderEvent(completedEvent("e-1"));

        verify(serviceHistory, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("A replayed declined fact does not create a second task")
    void deduplicatesDeclinedFollowUp() {
        when(processedEvents.existsById("e-2")).thenReturn(false);
        when(followUps.existsBySourceEventId("e-2")).thenReturn(true);

        listener.onWorkorderEvent(declinedEvent("e-2"));

        verify(followUps, never()).save(any());
        // Still recorded as processed so the offset advances.
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Unrelated workorder fact types are ignored")
    void ignoresOtherEventTypes() {
        listener.onWorkorderEvent("""
                {"eventId":"e-9","eventType":"workorder.workorder.updated","payload":{}}
                """);

        verify(serviceHistory, never()).save(any());
        verify(followUps, never()).save(any());
        verify(processedEvents, never()).save(any());
    }
}
