package com.positivity.customer.internal.config;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.customer.internal.event.EventEnvelope;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.ProcessingLogRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * RED-phase tests for {@link WorkorderEventHandler}.
 *
 * <p>All tests MUST FAIL — specifically, each test calls a handler method
 * directly so that the {@link UnsupportedOperationException} thrown by the stub
 * propagates out and JUnit records it as an ERROR. This provides deterministic
 * RED evidence for Story #92 (pos-customer inbound workorder event handler).</p>
 *
 * <p>Uses {@code @ExtendWith(MockitoExtension.class)} with manual construction
 * to avoid starting a full Spring context and triggering Kafka auto-configuration,
 * since the handler is gated by
 * {@code @ConditionalOnProperty(pos.customer.kafka.enabled=true)}.</p>
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

    private WorkorderEventHandler handler;

    @BeforeEach
    void setUp() {
        // Issue #92: real ObjectMapper required — handler deserialises raw JSON
        handler = new WorkorderEventHandler(
                processingLogRepository,
                communicationPreferenceRepository,
                personPartyRepository,
                new ObjectMapper()
        );
    }

    // -----------------------------------------------------------------------
    // AC-1  VehicleUpdated — new event (happy path)
    // -----------------------------------------------------------------------

    /**
     * AC-1: A fresh VehicleUpdated envelope must be processed by the handler.
     * RED: UnsupportedOperationException propagates from the stub — JUnit records ERROR.
     */
    @Test
    void handleVehicleUpdated_processesNewEvent_throwsUnsupportedOperationException() {
        EventEnvelope envelope = vehicleUpdatedEnvelope(UUID.randomUUID().toString());
        handler.handleVehicleUpdated(envelope);
    }

    // -----------------------------------------------------------------------
    // AC-2  VehicleUpdated — duplicate detection
    // -----------------------------------------------------------------------

    /**
     * AC-2: A VehicleUpdated envelope with a duplicate eventId must be skipped
     * (idempotency). RED: UnsupportedOperationException propagates from the stub.
     */
    @Test
    void handleVehicleUpdated_duplicateEvent_throwsUnsupportedOperationException() {
        String duplicateId = UUID.randomUUID().toString();
        EventEnvelope envelope = vehicleUpdatedEnvelope(duplicateId);
        handler.handleVehicleUpdated(envelope);
    }

    // -----------------------------------------------------------------------
    // AC-3  ContactPreferenceUpdated — new event (happy path)
    // -----------------------------------------------------------------------

    /**
     * AC-3: A fresh ContactPreferenceUpdated envelope must be processed.
     * RED: UnsupportedOperationException propagates from the stub.
     */
    @Test
    void handleContactPreferenceUpdated_processesNewEvent_throwsUnsupportedOperationException() {
        EventEnvelope envelope = contactPreferenceUpdatedEnvelope(UUID.randomUUID().toString());
        handler.handleContactPreferenceUpdated(envelope);
    }

    // -----------------------------------------------------------------------
    // AC-4  ContactPreferenceUpdated — duplicate detection
    // -----------------------------------------------------------------------

    /**
     * AC-4: A ContactPreferenceUpdated envelope with a duplicate eventId must be
     * skipped. RED: UnsupportedOperationException propagates from the stub.
     */
    @Test
    void handleContactPreferenceUpdated_duplicateEvent_throwsUnsupportedOperationException() {
        String duplicateId = UUID.randomUUID().toString();
        EventEnvelope envelope = contactPreferenceUpdatedEnvelope(duplicateId);
        handler.handleContactPreferenceUpdated(envelope);
    }

    // -----------------------------------------------------------------------
    // AC-5  PartyNoteAdded — new event (happy path)
    // -----------------------------------------------------------------------

    /**
     * AC-5: A fresh PartyNoteAdded envelope must be processed.
     * RED: UnsupportedOperationException propagates from the stub.
     */
    @Test
    void handlePartyNoteAdded_processesNewEvent_throwsUnsupportedOperationException() {
        EventEnvelope envelope = partyNoteAddedEnvelope(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        handler.handlePartyNoteAdded(envelope);
    }

    // -----------------------------------------------------------------------
    // AC-6  PartyNoteAdded — duplicate detection
    // -----------------------------------------------------------------------

    /**
     * AC-6: A PartyNoteAdded envelope with a duplicate eventId must be skipped.
     * RED: UnsupportedOperationException propagates from the stub.
     */
    @Test
    void handlePartyNoteAdded_duplicateEvent_throwsUnsupportedOperationException() {
        String duplicateId = UUID.randomUUID().toString();
        EventEnvelope envelope = partyNoteAddedEnvelope(duplicateId, UUID.randomUUID().toString());
        handler.handlePartyNoteAdded(envelope);
    }

    // -----------------------------------------------------------------------
    // AC-7  handleWorkorderEvent top-level dispatch
    // -----------------------------------------------------------------------

    /**
     * AC-7: The top-level Kafka listener entry-point must dispatch and process
     * the raw JSON message. RED: UnsupportedOperationException propagates from the stub.
     */
    @Test
    void handleWorkorderEvent_throwsUnsupportedOperationException() {
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"eventType\":\"VehicleUpdated\","
                + "\"eventVersion\":\"1.0\","
                + "\"sourceSystem\":\"WorkorderExecution\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"timestamp\":\"2026-03-03T10:00:00Z\","
                + "\"payload\":{}}";

        handler.handleWorkorderEvent(json);
    }

    // -----------------------------------------------------------------------
    // AC-8  Business rule violation — party not found
    // -----------------------------------------------------------------------

    /**
     * AC-8: A PartyNoteAdded event referencing a non-existent partyId must
     * result in a business-rule-violation outcome. RED: UnsupportedOperationException
     * propagates from the stub.
     */
    @Test
    void handlePartyNoteAdded_nonExistentParty_throwsUnsupportedOperationException() {
        String nonExistentPartyId = UUID.randomUUID().toString();
        EventEnvelope envelope = partyNoteAddedEnvelope(UUID.randomUUID().toString(), nonExistentPartyId);
        handler.handlePartyNoteAdded(envelope);
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
                        "color", "White"
                ))
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
                        "marketingPreference", "OPT_OUT"
                ))
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
                        "sourceWorkorderId", UUID.randomUUID().toString()
                ))
                .build();
    }
}
