package com.positivity.customer.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
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
 * Coverage-gap tests for {@link WorkorderEventHandler}.
 *
 * <p>Targets uncovered branches from Story #92: the
 * {@code ContactPreferenceUpdated} and {@code PartyNoteAdded} dispatch paths
 * in {@link WorkorderEventHandler#handleWorkorderEvent(String)}, the unknown
 * event type path, the {@link tools.jackson.core.JacksonException} path,
 * and the {@code catch(Exception)} branches in the three typed handlers.</p>
 *
 * Issue: #92
 */
@ExtendWith(MockitoExtension.class)
class WorkorderEventHandlerCoverageTest {

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
        handler = new WorkorderEventHandler(
                processingLogRepository,
                communicationPreferenceRepository,
                personPartyRepository,
                new ObjectMapper()
        );
    }

    // -----------------------------------------------------------------------
    // handleWorkorderEvent — ContactPreferenceUpdated dispatch
    // -----------------------------------------------------------------------

    /**
     * The top-level listener dispatches a {@code ContactPreferenceUpdated} JSON
     * message to the typed handler, which upserts the preference and saves a
     * {@link ProcessingStatus#SUCCESS} log.
     */
    @Test
    void handleWorkorderEvent_contactPreferenceUpdatedJson_dispatchesAndSavesSuccessLog() {
        String eventId = UUID.randomUUID().toString();
        String json = "{\"eventId\":\"" + eventId + "\","
                + "\"eventType\":\"ContactPreferenceUpdated\","
                + "\"eventVersion\":\"1.0\","
                + "\"sourceSystem\":\"WorkorderExecution\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"timestamp\":\"2026-03-04T10:00:00Z\","
                + "\"payload\":{"
                + "\"partyId\":\"" + UUID.randomUUID() + "\","
                + "\"emailPreference\":\"OPT_IN\","
                + "\"smsPreference\":\"OPT_OUT\","
                + "\"phonePreference\":\"OPT_IN\","
                + "\"marketingPreference\":\"OPT_OUT\""
                + "}}";

        handler.handleWorkorderEvent(json);

        verify(communicationPreferenceRepository).save(any());
        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SUCCESS);
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // handleWorkorderEvent — PartyNoteAdded dispatch
    // -----------------------------------------------------------------------

    /**
     * The top-level listener dispatches a {@code PartyNoteAdded} JSON message
     * to the typed handler for an existing party and saves a
     * {@link ProcessingStatus#SUCCESS} log.
     */
    @Test
    void handleWorkorderEvent_partyNoteAddedJson_dispatchesAndSavesSuccessLog() {
        String eventId = UUID.randomUUID().toString();
        String partyId = UUID.randomUUID().toString();
        when(personPartyRepository.findByPersonId(UUID.fromString(partyId)))
                .thenReturn(Optional.of(personParty));
        String json = "{\"eventId\":\"" + eventId + "\","
                + "\"eventType\":\"PartyNoteAdded\","
                + "\"eventVersion\":\"1.0\","
                + "\"sourceSystem\":\"WorkorderExecution\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"timestamp\":\"2026-03-04T10:00:00Z\","
                + "\"payload\":{"
                + "\"partyId\":\"" + partyId + "\","
                + "\"noteText\":\"Service note\","
                + "\"noteType\":\"SERVICE\","
                + "\"sourceWorkorderId\":\"" + UUID.randomUUID() + "\""
                + "}}";

        handler.handleWorkorderEvent(json);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SUCCESS);
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // handleWorkorderEvent — unknown event type → SCHEMA_VALIDATION_FAILED
    // -----------------------------------------------------------------------

    /**
     * An envelope carrying an unsupported {@code eventType} triggers the
     * warn-and-fail path, saving a {@link ProcessingStatus#SCHEMA_VALIDATION_FAILED}
     * log with the "Unsupported event type" failure reason.
     */
    @Test
    void handleWorkorderEvent_unknownEventType_savesSchemaValidationFailedLog() {
        String eventId = UUID.randomUUID().toString();
        String json = "{\"eventId\":\"" + eventId + "\","
                + "\"eventType\":\"UnrecognisedEvent\","
                + "\"eventVersion\":\"1.0\","
                + "\"sourceSystem\":\"WorkorderExecution\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"timestamp\":\"2026-03-04T10:00:00Z\","
                + "\"payload\":{}}";

        handler.handleWorkorderEvent(json);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SCHEMA_VALIDATION_FAILED);
        assertThat(captor.getValue().getFailureReason()).contains("Unsupported event type");
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // handleWorkorderEvent — malformed JSON → JacksonException path
    // -----------------------------------------------------------------------

    /**
     * A message that cannot be deserialized triggers the
     * {@link tools.jackson.core.JacksonException} catch path, saving a
     * {@link ProcessingStatus#SCHEMA_VALIDATION_FAILED} log with a generated
     * {@code eventId} prefixed with {@code "SCHEMA-"}.
     */
    @Test
    void handleWorkorderEvent_malformedJson_savesSchemaValidationFailedLogWithGeneratedId() {
        String malformedJson = "{ this is not valid json : at all }";

        handler.handleWorkorderEvent(malformedJson);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SCHEMA_VALIDATION_FAILED);
        assertThat(captor.getValue().getEventId()).startsWith("SCHEMA-");
        assertThat(captor.getValue().getEventType()).isEqualTo("UNKNOWN");
    }

    // -----------------------------------------------------------------------
    // handleContactPreferenceUpdated — catch(Exception) branch via bad partyId
    // -----------------------------------------------------------------------

    /**
     * An envelope with a malformed (non-UUID) {@code partyId} in the
     * {@code ContactPreferenceUpdated} payload causes {@code UUID.fromString} to
     * throw, exercising the {@code catch(Exception)} path and saving a
     * {@link ProcessingStatus#SCHEMA_VALIDATION_FAILED} log.
     */
    @Test
    void handleContactPreferenceUpdated_invalidPartyIdFormat_savesSchemaValidationFailedLog() {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = EventEnvelope.builder()
                .eventId(eventId)
                .eventType("ContactPreferenceUpdated")
                .eventVersion("1.0")
                .sourceSystem("WorkorderExecution")
                .correlationId(UUID.randomUUID().toString())
                .timestamp("2026-03-04T10:00:00Z")
                .payload(Map.of(
                        "partyId", "not-a-valid-uuid",
                        "emailPreference", "OPT_IN"
                ))
                .build();

        handler.handleContactPreferenceUpdated(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SCHEMA_VALIDATION_FAILED);
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // handlePartyNoteAdded — catch(Exception) branch via bad partyId
    // -----------------------------------------------------------------------

    /**
     * An envelope with a malformed (non-UUID) {@code partyId} in the
     * {@code PartyNoteAdded} payload causes {@code UUID.fromString} to throw,
     * exercising the {@code catch(Exception)} path and saving a
     * {@link ProcessingStatus#SCHEMA_VALIDATION_FAILED} log.
     */
    @Test
    void handlePartyNoteAdded_invalidPartyIdFormat_savesSchemaValidationFailedLog() {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = EventEnvelope.builder()
                .eventId(eventId)
                .eventType("PartyNoteAdded")
                .eventVersion("1.0")
                .sourceSystem("WorkorderExecution")
                .correlationId(UUID.randomUUID().toString())
                .timestamp("2026-03-04T10:00:00Z")
                .payload(Map.of(
                        "partyId", "not-a-valid-uuid",
                        "noteText", "some note",
                        "noteType", "SERVICE"
                ))
                .build();

        handler.handlePartyNoteAdded(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SCHEMA_VALIDATION_FAILED);
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // handleVehicleUpdated — catch(Exception) branch via save failure
    // -----------------------------------------------------------------------

    /**
     * A {@link RuntimeException} thrown by the first
     * {@link ProcessingLogRepository#save} call in {@link WorkorderEventHandler#handleVehicleUpdated}
     * exercises the {@code catch(Exception)} path and triggers a second save with
     * {@link ProcessingStatus#SCHEMA_VALIDATION_FAILED}.
     */
    @Test
    void handleVehicleUpdated_saveFails_savesSchemaValidationFailedLogOnRetry() {
        String eventId = UUID.randomUUID().toString();
        when(processingLogRepository.save(any()))
                .thenThrow(new RuntimeException("simulated DB failure"))
                .thenReturn(ProcessingLog.builder().build());
        EventEnvelope envelope = EventEnvelope.builder()
                .eventId(eventId)
                .eventType("VehicleUpdated")
                .eventVersion("1.0")
                .sourceSystem("WorkorderExecution")
                .correlationId(UUID.randomUUID().toString())
                .timestamp("2026-03-04T10:00:00Z")
                .payload(Map.of("vehicleId", UUID.randomUUID().toString(), "vin", "1HGBH41JXMN109186"))
                .build();

        handler.handleVehicleUpdated(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(ProcessingStatus.SCHEMA_VALIDATION_FAILED);
        assertThat(captor.getAllValues().get(1).getEventId()).isEqualTo(eventId);
    }

    // -----------------------------------------------------------------------
    // handleVehicleUpdated — null payload path
    // -----------------------------------------------------------------------

    /**
     * A {@code VehicleUpdated} envelope with a {@code null} payload map is
     * handled gracefully: the null-check falls back to {@link Map#of()} and
     * a {@link ProcessingStatus#SUCCESS} log is saved.
     */
    @Test
    void handleVehicleUpdated_nullPayload_treatsAsEmptyMapAndSavesSuccessLog() {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = EventEnvelope.builder()
                .eventId(eventId)
                .eventType("VehicleUpdated")
                .eventVersion("1.0")
                .sourceSystem("WorkorderExecution")
                .correlationId(UUID.randomUUID().toString())
                .timestamp("2026-03-04T10:00:00Z")
                .payload(null)
                .build();

        handler.handleVehicleUpdated(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SUCCESS);
    }

    // -----------------------------------------------------------------------
    // handleContactPreferenceUpdated — null payload path
    // -----------------------------------------------------------------------

    /**
     * A {@code ContactPreferenceUpdated} envelope with a {@code null} payload
     * falls back to an empty map. With no {@code partyId} key the partyId
     * resolves to an empty string, causing {@code UUID.fromString("")} to throw,
     * so SCHEMA_VALIDATION_FAILED is saved.
     */
    @Test
    void handleContactPreferenceUpdated_nullPayload_savesSchemaValidationFailedLog() {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = EventEnvelope.builder()
                .eventId(eventId)
                .eventType("ContactPreferenceUpdated")
                .eventVersion("1.0")
                .sourceSystem("WorkorderExecution")
                .correlationId(UUID.randomUUID().toString())
                .timestamp("2026-03-04T10:00:00Z")
                .payload(null)
                .build();

        handler.handleContactPreferenceUpdated(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SCHEMA_VALIDATION_FAILED);
    }

    // -----------------------------------------------------------------------
    // handlePartyNoteAdded — null payload path
    // -----------------------------------------------------------------------

    /**
     * A {@code PartyNoteAdded} envelope with a {@code null} payload falls back
     * to an empty map. With no {@code partyId} key the partyId resolves to an
     * empty string, causing {@code UUID.fromString("")} to throw,
     * so SCHEMA_VALIDATION_FAILED is saved.
     */
    @Test
    void handlePartyNoteAdded_nullPayload_savesSchemaValidationFailedLog() {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = EventEnvelope.builder()
                .eventId(eventId)
                .eventType("PartyNoteAdded")
                .eventVersion("1.0")
                .sourceSystem("WorkorderExecution")
                .correlationId(UUID.randomUUID().toString())
                .timestamp("2026-03-04T10:00:00Z")
                .payload(null)
                .build();

        handler.handlePartyNoteAdded(envelope);

        ArgumentCaptor<ProcessingLog> captor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(processingLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingStatus.SCHEMA_VALIDATION_FAILED);
    }
}
