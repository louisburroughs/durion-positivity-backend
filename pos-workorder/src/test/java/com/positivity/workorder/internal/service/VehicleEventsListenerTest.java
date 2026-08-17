package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.ExtVehicleReplica;
import com.positivity.workorder.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Replicating vehicle identity, for naming a vehicle to a fleet program (#1346).
 *
 * <p>This is not a general-purpose vehicle mirror; only what identifies a vehicle to a fleet is
 * copied. The stale-version guard is the one behaviour shared with every other replica consumer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VehicleEventsListener — the ext_vehicle replica (#1346)")
class VehicleEventsListenerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");
    private static final UUID VEHICLE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000e1");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtVehicleReplicaRepository vehicleRepository;

    private VehicleEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new VehicleEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                JsonMapper.builder().build(),
                processedEventRepository,
                vehicleRepository);
        when(processedEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("identifying fields are replicated: plate, VIN, unit number")
    void identifyingFieldsAreReplicated() {
        when(processedEventRepository.existsById("e-1")).thenReturn(false);
        when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.empty());

        listener.onVehicleEvent("""
                {"eventId":"e-1","eventType":"vehicle.vehicle.updated","schemaVersion":1,
                 "aggregateId":"019200aa-0000-7000-8000-0000000000e1","aggregateVersion":5,
                 "payload":{"vehicleId":"019200aa-0000-7000-8000-0000000000e1",
                            "accountId":"019200aa-0000-7000-8000-0000000000f1",
                            "vin":"1HGCM82633A004352","vinNormalized":"1HGCM82633A004352","active":true,
                            "licensePlate":"ABC-123","unitNumber":"FLEET-9"}}
                """);

        ArgumentCaptor<ExtVehicleReplica> saved = ArgumentCaptor.forClass(ExtVehicleReplica.class);
        verify(vehicleRepository).save(saved.capture());
        assertThat(saved.getValue().getVin()).isEqualTo("1HGCM82633A004352");
        assertThat(saved.getValue().getLicensePlate()).isEqualTo("ABC-123");
        assertThat(saved.getValue().getUnitNumber()).isEqualTo("FLEET-9");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("a strictly-lower version is not applied over a newer replica row")
    void staleVersionIsIgnored() {
        when(processedEventRepository.existsById("e-2")).thenReturn(false);
        ExtVehicleReplica existing = new ExtVehicleReplica();
        existing.setVehicleId(VEHICLE_ID);
        existing.setAggregateVersion(10L);
        when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(existing));

        listener.onVehicleEvent("""
                {"eventId":"e-2","eventType":"vehicle.vehicle.updated","schemaVersion":1,
                 "aggregateId":"019200aa-0000-7000-8000-0000000000e1","aggregateVersion":3,
                 "payload":{"vehicleId":"019200aa-0000-7000-8000-0000000000e1",
                            "accountId":"019200aa-0000-7000-8000-0000000000f1",
                            "vin":"1HGCM82633A004352","vinNormalized":"1HGCM82633A004352","active":true}}
                """);

        verify(vehicleRepository, never()).save(any());
    }
}
