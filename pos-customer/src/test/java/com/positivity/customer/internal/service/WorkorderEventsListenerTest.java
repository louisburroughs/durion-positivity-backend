package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.CustomerInteraction;
import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.entity.PartyNote;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.entity.ServiceHistory;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.customer.internal.enums.InteractionDirection;
import com.positivity.customer.internal.enums.InteractionType;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PartyNoteRepository;
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
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link WorkorderEventsListener} (FI-3, #1133): completion facts project into
 * {@code service_history}, declined facts raise exactly one {@code DECLINED_SERVICE_FOLLOWUP}
 * task, note facts project onto the party timeline (#1584), all idempotent, and unrelated
 * workorder fact types are ignored.
 */
class WorkorderEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();
    private static final UUID WORKORDER_ID = UUID.fromString("01980a58-0000-7000-8000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-000000000002");
    private static final UUID VEHICLE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000003");
    private static final UUID LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000004");
    private static final UUID NOTE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000005");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ServiceHistoryRepository serviceHistory = mock(ServiceHistoryRepository.class);
    private final FollowUpTaskRepository followUps = mock(FollowUpTaskRepository.class);
    private final PartyNoteRepository partyNotes = mock(PartyNoteRepository.class);
    private final CustomerInteractionServiceImpl interactions = mock(CustomerInteractionServiceImpl.class);

    private WorkorderEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new WorkorderEventsListener(
                TEST_CLOCK,
                MAPPER,
                processedEvents,
                serviceHistory,
                followUps,
                partyNotes,
                interactions,
                mock(ObjectProvider.class));
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

    private String noteEvent(String eventId, String partyId) {
        return """
                {"eventId":"%s","eventType":"workorder.note.added.v1","sourceService":"pos-workorder",
                 "payload":{"workorderId":"%s","workorderNumber":"WO-2026-1001","noteId":"%s","partyId":%s,
                            "noteType":"CUSTOMER_REQUEST",
                            "noteText":"Customer says the noise only happens on a cold start.",
                            "authoredBy":"advisor@example.com","addedAt":"2026-07-20T11:00:00Z"}}
                """.formatted(eventId, WORKORDER_ID, NOTE_ID, partyId == null ? "null" : "\"" + partyId + "\"");
    }

    @Test
    @DisplayName("A note fact writes party_note and a WORKORDER_NOTE interaction")
    void projectsNote() {
        when(processedEvents.existsById("e-5")).thenReturn(false);
        when(partyNotes.existsBySourceEventId("e-5")).thenReturn(false);

        listener.onWorkorderEvent(noteEvent("e-5", PARTY_ID.toString()));

        ArgumentCaptor<PartyNote> noteCaptor = ArgumentCaptor.forClass(PartyNote.class);
        verify(partyNotes).save(noteCaptor.capture());
        PartyNote note = noteCaptor.getValue();
        assertThat(note.getPartyId()).isEqualTo(PARTY_ID);
        assertThat(note.getNoteType()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(note.getNoteText()).isEqualTo("Customer says the noise only happens on a cold start.");
        assertThat(note.getSourceWorkorderId()).isEqualTo(WORKORDER_ID.toString());
        assertThat(note.getSourceEventId()).isEqualTo("e-5");

        ArgumentCaptor<CustomerInteraction> interactionCaptor = ArgumentCaptor.forClass(CustomerInteraction.class);
        verify(interactions).ingest(interactionCaptor.capture());
        CustomerInteraction interaction = interactionCaptor.getValue();
        assertThat(interaction.getType()).isEqualTo(InteractionType.WORKORDER_NOTE);
        assertThat(interaction.getDirection()).isEqualTo(InteractionDirection.INBOUND);
        assertThat(interaction.getPartyId()).isEqualTo(PARTY_ID);
        assertThat(interaction.getBody()).isEqualTo("Customer says the noise only happens on a cold start.");
        assertThat(interaction.getOccurredAt()).isEqualTo(Instant.parse("2026-07-20T11:00:00Z"));
        // The author has to survive the hop, or a CSR cannot tell who recorded the note.
        assertThat(interaction.getActor()).isEqualTo("advisor@example.com");
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("A note on a no-customer job projects nothing")
    void skipsNoteWithoutParty() {
        when(processedEvents.existsById("e-6")).thenReturn(false);

        listener.onWorkorderEvent(noteEvent("e-6", null));

        verify(partyNotes, never()).save(any());
        verify(interactions, never()).ingest(any());
        // Still recorded as processed so the offset advances.
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("A replayed note fact does not write a second party_note row")
    void deduplicatesNoteRow() {
        when(processedEvents.existsById("e-7")).thenReturn(false);
        when(partyNotes.existsBySourceEventId("e-7")).thenReturn(true);

        listener.onWorkorderEvent(noteEvent("e-7", PARTY_ID.toString()));

        verify(partyNotes, never()).save(any());
        // The timeline half is idempotent on its own source_event_id, so it is still offered.
        verify(interactions).ingest(any(CustomerInteraction.class));
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
        verify(partyNotes, never()).save(any());
        verify(processedEvents, never()).save(any());
    }
}
