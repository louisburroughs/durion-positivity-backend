package com.positivity.customer.internal.event.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.entity.ProcessingLog;
import com.positivity.customer.internal.enums.ProcessingStatus;
import com.positivity.customer.internal.event.EventEnvelope;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.ProcessingLogRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * GREEN-phase unit tests for {@link WorkorderEventHandler}.
 *
 * <p>
 * Verifies the full processing contract for inbound workorder-originated
 * events:
 * idempotency (SKIPPED_DUPLICATE), happy-path persistence (SUCCESS), business
 * rule violation handling (BUSINESS_RULE_VIOLATION), and exception isolation
 * (no exception may escape to the Kafka broker/retry mechanism).
 * </p>
 *
 * <p>
 * Uses {@code @ExtendWith(MockitoExtension.class)} with manual handler
 * construction
 * to avoid starting a full Spring context and triggering Kafka
 * auto-configuration,
 * since the handler is gated by
 * {@code @ConditionalOnProperty(pos.customer.kafka.enabled=true)}.
 * </p>
 *
 * Issue: #92
 */
@ExtendWith(MockitoExtension.class)
class WorkorderEventHandlerTest {

    @Mock
    private ProcessingLogRepository processingLogRepository;

    @Mock
    private CommunicationPreferenceRepository communicationPreferenceRepository;

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private PersonParty personParty;

    private WorkorderEventHandler handler;

    @BeforeEach
    void setUp() {
        // Real ObjectMapper required — handler deserializes raw JSON strings
        handler = new WorkorderEventHandler(
                processingLogRepository,
                communicationPreferenceRepository,
                personPartyRepository,
                new ObjectMapper());
    }

    // -----------------------------------------------------------------------
    // AC-1 VehicleUpdated — new event (happy path)
    // -----------------------------------------------------------------------

    /**
     * AC-1: A fresh {@code VehicleUpdated} envelope is processed and a
     * {@link ProcessingStatus#SUCCESS} {@link ProcessingLog} entry is saved.
     */
    @Test
    void handleVehicleUpdated_novelEvent_savesSuccessLog() {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = vehicleUpdatedEnvelope(eventId);

        handler.handleVehicleUpdated(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SUCCESS);
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // AC-2 VehicleUpdated — duplicate detection
    // -----------------------------------------------------------------------

    /**
     * AC-2: A {@code VehicleUpdated} envelope with a duplicate {@code eventId}
     * causes a {@link ProcessingStatus#SKIPPED_DUPLICATE} {@link ProcessingLog}
     * to be saved with a surrogate eventId, leaving the original record intact.
     */
    @Test
    void handleVehicleUpdated_duplicateEvent_savesSkippedDuplicateLog() {
        String duplicateId = UUID.randomUUID().toString();
        when(processingLogRepository.findByEventId(duplicateId))
                .thenReturn(Optional.of(ProcessingLog.builder()
                        .eventId(duplicateId)
                        .status(ProcessingStatus.SUCCESS)
                        .build()));
        EventEnvelope envelope = vehicleUpdatedEnvelope(duplicateId);

        handler.handleVehicleUpdated(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SKIPPED_DUPLICATE);
        assertThat(captor.getValue().getFailureReason()).contains(duplicateId);
    }

    // -----------------------------------------------------------------------
    // AC-3 ContactPreferenceUpdated — new event (happy path)
    // -----------------------------------------------------------------------

    /**
     * AC-3: A fresh {@code ContactPreferenceUpdated} envelope is processed,
     * the CommunicationPreference is upserted, and a
     * {@link ProcessingStatus#SUCCESS}
     * {@link ProcessingLog} is saved.
     */
    @Test
    void handleContactPreferenceUpdated_novelEvent_upsertsPreferenceAndSavesSuccessLog() {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = contactPreferenceUpdatedEnvelope(eventId);

        handler.handleContactPreferenceUpdated(envelope);

        verify(communicationPreferenceRepository).save(any());
        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SUCCESS);
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // AC-4 ContactPreferenceUpdated — duplicate detection
    // -----------------------------------------------------------------------

    /**
     * AC-4: A {@code ContactPreferenceUpdated} envelope with a duplicate
     * {@code eventId} causes no preference update and saves a
     * {@link ProcessingStatus#SKIPPED_DUPLICATE} {@link ProcessingLog} with a
     * surrogate eventId.
     */
    @Test
    void handleContactPreferenceUpdated_duplicateEvent_savesSkippedDuplicateLog() {
        String duplicateId = UUID.randomUUID().toString();
        when(processingLogRepository.findByEventId(duplicateId))
                .thenReturn(Optional.of(ProcessingLog.builder()
                        .eventId(duplicateId)
                        .status(ProcessingStatus.SUCCESS)
                        .build()));
        EventEnvelope envelope = contactPreferenceUpdatedEnvelope(duplicateId);

        handler.handleContactPreferenceUpdated(envelope);

        verify(communicationPreferenceRepository, never()).save(any());
        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SKIPPED_DUPLICATE);
        assertThat(captor.getValue().getFailureReason()).contains(duplicateId);
    }

    // -----------------------------------------------------------------------
    // AC-5 PartyNoteAdded — new event, party found (happy path)
    // -----------------------------------------------------------------------

    /**
     * AC-5a: A fresh {@code PartyNoteAdded} envelope for an existing party is
     * acknowledged, and a {@link ProcessingStatus#SUCCESS} {@link ProcessingLog}
     * is saved.
     */
    @Test
    void handlePartyNoteAdded_novelEventPartyFound_savesSuccessLog() {
        String eventId = UUID.randomUUID().toString();
        String partyId = UUID.randomUUID().toString();
        when(personPartyRepository.findByPersonId(UUID.fromString(partyId)))
                .thenReturn(Optional.of(personParty));
        EventEnvelope envelope = partyNoteAddedEnvelope(eventId, partyId);

        handler.handlePartyNoteAdded(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SUCCESS);
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // AC-6 PartyNoteAdded — duplicate detection
    // -----------------------------------------------------------------------

    /**
     * AC-6: A {@code PartyNoteAdded} envelope with a duplicate {@code eventId}
     * saves a {@link ProcessingStatus#SKIPPED_DUPLICATE} {@link ProcessingLog}
     * with a surrogate eventId.
     */
    @Test
    void handlePartyNoteAdded_duplicateEvent_savesSkippedDuplicateLog() {
        String duplicateId = UUID.randomUUID().toString();
        when(processingLogRepository.findByEventId(duplicateId))
                .thenReturn(Optional.of(ProcessingLog.builder()
                        .eventId(duplicateId)
                        .status(ProcessingStatus.SUCCESS)
                        .build()));
        EventEnvelope envelope = partyNoteAddedEnvelope(duplicateId, UUID.randomUUID().toString());

        handler.handlePartyNoteAdded(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SKIPPED_DUPLICATE);
        assertThat(captor.getValue().getFailureReason()).contains(duplicateId);
    }

    // -----------------------------------------------------------------------
    // AC-7 handleWorkorderEvent — top-level JSON dispatch
    // -----------------------------------------------------------------------

    /**
     * AC-7: The top-level Kafka listener dispatches a {@code VehicleUpdated} JSON
     * message to the correct typed handler and saves a
     * {@link ProcessingStatus#SUCCESS}
     * {@link ProcessingLog}.
     */
    @Test
    void handleWorkorderEvent_vehicleUpdatedJson_dispatchesAndSavesSuccessLog() {
        String eventId = UUID.randomUUID().toString();
        String json = "{\"eventId\":\"" + eventId + "\","
                + "\"eventType\":\"VehicleUpdated\","
                + "\"eventVersion\":\"1.0\","
                + "\"sourceSystem\":\"WorkorderExecution\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"timestamp\":\"2026-03-03T10:00:00Z\","
                + "\"payload\":{}}";

        handler.handleWorkorderEvent(json);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SUCCESS);
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // Exception isolation — failure in handler must not propagate (AC-6 / isolation
    // req)
    // -----------------------------------------------------------------------

    /**
     * Failure isolation: an unhandled {@link RuntimeException} from a typed
     * handler must NOT propagate out of
     * {@link WorkorderEventHandler#handleWorkorderEvent(String)}.
     * This prevents Kafka from entering a retry/poison-pill loop on a
     * persistently-failing message.
     *
     * <p>
     * F-02: The generic {@code catch(Exception)} block must also persist a
     * {@link ProcessingLog} with {@link ProcessingStatus#SCHEMA_VALIDATION_FAILED}
     * so the failure is auditable without crashing the consumer.
     * </p>
     */
    @Test
    void handleWorkorderEvent_handlerThrowsException_doesNotPropagateAndSavesSchemaValidationFailedLog() {
        when(processingLogRepository.findByEventId(any()))
                .thenThrow(new RuntimeException("simulated DB error"));
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"eventType\":\"VehicleUpdated\","
                + "\"eventVersion\":\"1.0\","
                + "\"sourceSystem\":\"WorkorderExecution\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"timestamp\":\"2026-03-03T10:00:00Z\","
                + "\"payload\":{}}";

        assertDoesNotThrow(() -> handler.handleWorkorderEvent(json));

        // F-02: generic catch block must save a SCHEMA_VALIDATION_FAILED processing log
        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SCHEMA_VALIDATION_FAILED);
        assertThat(captor.getValue().getEventId())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // -----------------------------------------------------------------------
    // AC-8 PartyNoteAdded — business rule violation (party not found)
    // -----------------------------------------------------------------------

    /**
     * AC-8: A {@code PartyNoteAdded} event referencing a non-existent
     * {@code partyId} results in a
     * {@link ProcessingStatus#BUSINESS_RULE_VIOLATION} {@link ProcessingLog}.
     * No exception is propagated to the caller.
     */
    @Test
    void handlePartyNoteAdded_partyNotFound_savesBusinessRuleViolationLog() {
        String eventId = UUID.randomUUID().toString();
        String nonExistentPartyId = UUID.randomUUID().toString();
        // personPartyRepository.findByPersonId returns Optional.empty() by default
        EventEnvelope envelope = partyNoteAddedEnvelope(eventId, nonExistentPartyId);

        handler.handlePartyNoteAdded(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.BUSINESS_RULE_VIOLATION);
        assertThat(captor.getValue().getFailureReason()).contains("Party not found");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private EventEnvelope vehicleUpdatedEnvelope(String eventId) {
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType("VehicleUpdated")
                .eventVersion("1.0")
                .sourceSystem("WorkorderExecution")
                .correlationId(UUID.randomUUID().toString())
                .timestamp("2026-03-03T10:00:00Z")
                .payload(Map.of(
                        "vehicleId", UUID.randomUUID().toString(),
                        "vin", "1HGBH41JXMN109186",
                        "make", "Honda",
                        "model", "Accord",
                        "year", 2022,
                        "color", "White"))
                .build();
    }

    private EventEnvelope contactPreferenceUpdatedEnvelope(String eventId) {
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType("ContactPreferenceUpdated")
                .eventVersion("1.0")
                .sourceSystem("WorkorderExecution")
                .correlationId(UUID.randomUUID().toString())
                .timestamp("2026-03-03T10:00:00Z")
                .payload(Map.of(
                        "partyId", UUID.randomUUID().toString(),
                        "emailPreference", "OPT_IN",
                        "smsPreference", "OPT_OUT",
                        "phonePreference", "OPT_IN",
                        "marketingPreference", "OPT_OUT"))
                .build();
    }

    private EventEnvelope partyNoteAddedEnvelope(String eventId, String partyId) {
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType("PartyNoteAdded")
                .eventVersion("1.0")
                .sourceSystem("WorkorderExecution")
                .correlationId(UUID.randomUUID().toString())
                .timestamp("2026-03-03T10:00:00Z")
                .payload(Map.of(
                        "partyId", partyId,
                        "noteText", "Vehicle service note from workorder",
                        "noteType", "SERVICE",
                        "sourceWorkorderId", UUID.randomUUID().toString()))
                .build();
    }
}
