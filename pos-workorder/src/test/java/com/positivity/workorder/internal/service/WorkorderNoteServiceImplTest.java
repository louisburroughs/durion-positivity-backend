package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.workorder.WorkorderNoteAddedV1;
import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.dto.AddWorkorderNoteRequest;
import com.positivity.workorder.internal.dto.WorkorderNoteResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderNote;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.WorkorderNoteRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Instant;
import java.util.List;
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

/**
 * Customer notes on a workorder (#1584).
 *
 * <p>The point of the feature is the fact: pos-customer's projection exists only because this
 * publishes. So what is pinned here is that saving a note publishes {@code
 * workorder.note.added.v1} carrying the workorder's party, and that the note still saves when
 * Kafka is off.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderNoteService — recording a customer note and publishing the fact (#1584)")
class WorkorderNoteServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-000000000101");
    private static final UUID NOTE_ID = UUID.fromString("019200aa-0000-7000-8000-000000000102");
    private static final UUID PARTY_ID = UUID.fromString("019200aa-0000-7000-8000-000000000103");
    private static final UUID VEHICLE_ID = UUID.fromString("019200aa-0000-7000-8000-000000000104");
    private static final UUID SHOP_ID = UUID.fromString("019200aa-0000-7000-8000-000000000105");
    private static final String NOTE_TEXT = "Customer says the noise only happens on a cold start.";

    @Mock
    private WorkorderNoteRepository workorderNoteRepository;

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriterProvider;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    private WorkorderNoteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkorderNoteServiceImpl(workorderNoteRepository, workorderRepository, outboxEventWriterProvider);
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder()));
        when(workorderNoteRepository.save(any(WorkorderNote.class))).thenAnswer(invocation -> {
            WorkorderNote note = invocation.getArgument(0);
            note.setNoteId(NOTE_ID);
            note.setCreatedAt(NOW);
            return note;
        });
    }

    private static Workorder workorder() {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setWorkorderNumber("WO-2026-1001");
        workorder.setCustomerId(PARTY_ID);
        workorder.setVehicleId(VEHICLE_ID);
        workorder.setShopId(SHOP_ID);
        return workorder;
    }

    @Test
    @DisplayName("Saves the note and publishes the fact with the workorder's party")
    void publishesFact() {
        WorkorderNoteResponse response = service.addNote(
                WORKORDER_ID, new AddWorkorderNoteRequest("CUSTOMER_REQUEST", NOTE_TEXT), "advisor@example.com");

        assertThat(response.noteId()).isEqualTo(NOTE_ID);
        assertThat(response.authoredBy()).isEqualTo("advisor@example.com");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter)
                .publish(
                        eq(WorkorderNoteAddedV1.EVENT_TYPE),
                        eq(WorkorderNoteAddedV1.SCHEMA_VERSION),
                        eq(WORKORDER_ID),
                        payload.capture());
        WorkorderNoteAddedV1 fact = (WorkorderNoteAddedV1) payload.getValue();
        assertThat(fact.partyId()).isEqualTo(PARTY_ID);
        assertThat(fact.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(fact.shopId()).isEqualTo(SHOP_ID);
        assertThat(fact.noteId()).isEqualTo(NOTE_ID);
        assertThat(fact.noteType()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(fact.noteText()).isEqualTo(NOTE_TEXT);
        assertThat(fact.workorderNumber()).isEqualTo("WO-2026-1001");
        assertThat(fact.addedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("A no-customer job publishes a fact with no party, which the consumer skips")
    void publishesFactWithoutParty() {
        Workorder noCustomer = workorder();
        noCustomer.setCustomerId(null);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(noCustomer));

        service.addNote(WORKORDER_ID, new AddWorkorderNoteRequest(null, NOTE_TEXT), null);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter)
                .publish(eq(WorkorderNoteAddedV1.EVENT_TYPE), anyInt(), eq(WORKORDER_ID), payload.capture());
        assertThat(((WorkorderNoteAddedV1) payload.getValue()).partyId()).isNull();
    }

    @Test
    @DisplayName("With Kafka off the note is still recorded")
    void savesWithoutPublisher() {
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(null);

        WorkorderNoteResponse response =
                service.addNote(WORKORDER_ID, new AddWorkorderNoteRequest(null, NOTE_TEXT), null);

        assertThat(response.noteText()).isEqualTo(NOTE_TEXT);
        verify(workorderNoteRepository).save(any(WorkorderNote.class));
        verifyNoInteractions(outboxEventWriter);
    }

    @Test
    @DisplayName("Blank note type and author are stored as null rather than empty strings")
    void normalizesBlanks() {
        service.addNote(WORKORDER_ID, new AddWorkorderNoteRequest("  ", "  " + NOTE_TEXT + "  "), "   ");

        ArgumentCaptor<WorkorderNote> saved = ArgumentCaptor.forClass(WorkorderNote.class);
        verify(workorderNoteRepository).save(saved.capture());
        assertThat(saved.getValue().getNoteType()).isNull();
        assertThat(saved.getValue().getAuthoredBy()).isNull();
        assertThat(saved.getValue().getNoteText()).isEqualTo(NOTE_TEXT);
    }

    @Test
    @DisplayName("An unknown workorder is a 404, and nothing is published")
    void rejectsUnknownWorkorder() {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addNote(WORKORDER_ID, new AddWorkorderNoteRequest(null, NOTE_TEXT), null))
                .isInstanceOf(WorkorderNotFoundException.class);

        verify(workorderNoteRepository, never()).save(any());
        verifyNoInteractions(outboxEventWriter);
    }

    @Test
    @DisplayName("A blank note is rejected as a bad argument, not an NPE, for non-HTTP callers")
    void rejectsBlankNoteText() {
        assertThatThrownBy(() -> service.addNote(WORKORDER_ID, new AddWorkorderNoteRequest(null, "  "), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noteText");
        assertThatThrownBy(() -> service.addNote(WORKORDER_ID, new AddWorkorderNoteRequest(null, null), null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(workorderNoteRepository, never()).save(any());
        verifyNoInteractions(outboxEventWriter);
    }

    @Test
    @DisplayName("Notes are listed newest first for an existing workorder")
    void listsNotes() {
        when(workorderRepository.existsById(WORKORDER_ID)).thenReturn(true);
        when(workorderNoteRepository.findByWorkorderIdOrderByCreatedAtDescNoteIdDesc(WORKORDER_ID))
                .thenReturn(List.of(WorkorderNote.builder()
                        .noteId(NOTE_ID)
                        .workorderId(WORKORDER_ID)
                        .noteText(NOTE_TEXT)
                        .createdAt(NOW)
                        .build()));

        assertThat(service.listNotes(WORKORDER_ID))
                .singleElement()
                .satisfies(note -> assertThat(note.noteText()).isEqualTo(NOTE_TEXT));
    }

    @Test
    @DisplayName("Listing notes for an unknown workorder is a 404")
    void listRejectsUnknownWorkorder() {
        when(workorderRepository.existsById(WORKORDER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.listNotes(WORKORDER_ID)).isInstanceOf(WorkorderNotFoundException.class);
    }
}
