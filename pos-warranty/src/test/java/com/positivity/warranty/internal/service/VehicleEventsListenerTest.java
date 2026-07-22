package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.entity.ExtVehicleReplica;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer contract of the vehicle.events.v1 listener (issue #924): manual envelope parse,
 * processed_events dedupe, strictly-below stale guard on aggregateVersion, ignored types still
 * recorded, transient DB errors rethrown for container retry/DLQ.
 */
class VehicleEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID VEHICLE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000901");
    private static final UUID ACCOUNT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000902");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtVehicleReplicaRepository extVehicleReplica = mock(ExtVehicleReplicaRepository.class);

    private VehicleEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new VehicleEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, extVehicleReplica);
    }

    private static String updatedEvent(String eventId, long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"vehicle.vehicle.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"vehicleId":"%s","accountId":"%s","vin":"1HGCM82633A004352",
                            "vinNormalized":"1HGCM82633A004352","active":true,
                            "odometerValue":42000,"odometerUnit":"MILES",
                            "createdAt":"2026-07-01T09:00:00Z","updatedAt":"2026-07-10T23:30:00Z"}}
                """
                .formatted(eventId, VEHICLE_ID, aggregateVersion, VEHICLE_ID, ACCOUNT_ID);
    }

    @Test
    @DisplayName("Upserts the ext_vehicle replica from a vehicle.vehicle.updated fact and records the eventId")
    void upsertsReplica() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(extVehicleReplica.findById(VEHICLE_ID)).thenReturn(Optional.empty());

        listener.onVehicleEvent(updatedEvent("e-1", 7));

        ArgumentCaptor<ExtVehicleReplica> captor = ArgumentCaptor.captor();
        verify(extVehicleReplica).save(captor.capture());
        ExtVehicleReplica saved = captor.getValue();
        assertThat(saved.getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(saved.getVin()).isEqualTo("1HGCM82633A004352");
        assertThat(saved.getOdometerValue()).isEqualTo(42_000);
        assertThat(saved.getOdometerUnit()).isEqualTo("MILES");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getAggregateVersion()).isEqualTo(7L);

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.captor();
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("e-1");
        assertThat(processed.getValue().getOwner()).isEqualTo(VehicleEventsListener.OWNER);
    }

    @Test
    @DisplayName("Drops a stale fact (older aggregateVersion) but still records it processed")
    void dropsStaleFact() {
        when(processedEvents.existsById("e-stale")).thenReturn(false);
        when(extVehicleReplica.findById(VEHICLE_ID))
                .thenReturn(Optional.of(ExtVehicleReplica.builder()
                        .vehicleId(VEHICLE_ID)
                        .aggregateVersion(9L)
                        .updatedAt(Instant.parse("2026-07-14T00:00:00Z"))
                        .build()));

        listener.onVehicleEvent(updatedEvent("e-stale", 7));

        verify(extVehicleReplica, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onVehicleEvent(updatedEvent("e-dup", 7));

        verify(extVehicleReplica, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Ignores other event types but still records them processed")
    void ignoresOtherEventTypesButRecordsThem() {
        when(processedEvents.existsById("e-2")).thenReturn(false);

        listener.onVehicleEvent("""
                {"eventId":"e-2","eventType":"vehicle.something.else","payload":{}}
                """);

        verify(extVehicleReplica, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips unparsable messages without recording anything")
    void skipsUnparsableMessages() {
        listener.onVehicleEvent("this is not json");

        verify(extVehicleReplica, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Skips events without an eventId")
    void skipsMissingEventId() {
        listener.onVehicleEvent("""
                {"eventType":"vehicle.vehicle.updated","payload":{}}
                """);

        verify(extVehicleReplica, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Records a malformed payload as processed instead of poisoning the partition")
    void malformedPayloadIsSwallowedButRecorded() {
        when(processedEvents.existsById("e-3")).thenReturn(false);

        listener.onVehicleEvent("""
                {"eventId":"e-3","eventType":"vehicle.vehicle.updated",
                 "payload":{"vehicleId":"not-a-uuid"}}
                """);

        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        when(extVehicleReplica.findById(VEHICLE_ID)).thenReturn(Optional.empty());
        when(extVehicleReplica.save(any())).thenThrow(new QueryTimeoutException("db"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onVehicleEvent(updatedEvent("e-4", 7)));

        verify(processedEvents, never()).save(any());
    }
}
