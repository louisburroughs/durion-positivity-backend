package com.positivity.vehicle.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.vehicle.VehicleCarePreferenceUpdatedV1;
import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link CarePreferenceEventPublisher} (#1175).
 */
class CarePreferenceEventPublisherTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final EntityManager entityManager = mock(EntityManager.class);
    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private CarePreferenceEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new CarePreferenceEventPublisher(TEST_CLOCK, entityManager, writerProvider, "vehicle.events.v1");
    }

    private VehicleRecord vehicleRecord() {
        return VehicleRecord.builder()
                .vehicleId(VEHICLE_ID)
                .accountId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .vin("1HGBH41JXMN109186")
                .vinNormalized("1HGBH41JXMN109186")
                .unitNumber("UNIT-1")
                .description("2022 Honda Accord")
                .build();
    }

    private VehicleCarePreference preference() {
        return VehicleCarePreference.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000010"))
                .vehicle(vehicleRecord())
                .preferences(Map.of("oilType", "synthetic"))
                .serviceIntervalMonths(4)
                .updatedAt(Instant.parse("2026-07-12T11:00:00Z"))
                .createdByUserId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
                .updatedByUserId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
                .version(2L)
                .build();
    }

    @Test
    @DisplayName("Queues a vehicle.care-preference.updated envelope keyed by vehicleId with an epoch-millis version")
    void queuesUpsertEnvelope() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);

        publisher.publishCarePreferenceUpserted(preference());

        // Auditing must have stamped the row before the payload snapshot is taken.
        verify(entityManager).flush();
        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DomainEventEnvelope> envelope = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(topic.capture(), envelope.capture());
        assertThat(topic.getValue()).isEqualTo("vehicle.events.v1");
        assertThat(envelope.getValue().eventType()).isEqualTo(VehicleCarePreferenceUpdatedV1.EVENT_TYPE);
        assertThat(envelope.getValue().aggregateId()).isEqualTo(VEHICLE_ID);
        // LWW stamp, not the row's JPA @Version: delete + re-create restarts the row version.
        assertThat(envelope.getValue().aggregateVersion())
                .isEqualTo(Instant.parse("2026-07-12T12:00:00Z").toEpochMilli());
        assertThat(envelope.getValue().sourceService()).isEqualTo("pos-vehicle-inventory");
        VehicleCarePreferenceUpdatedV1 payload =
                (VehicleCarePreferenceUpdatedV1) envelope.getValue().payload();
        assertThat(payload.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(payload.serviceIntervalMonths()).isEqualTo(4);
        assertThat(payload.deleted()).isFalse();
        assertThat(payload.updatedAt()).isEqualTo(Instant.parse("2026-07-12T11:00:00Z"));
    }

    @Test
    @DisplayName("Delete queues a tombstone with a null interval")
    void queuesDeleteTombstone() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);

        publisher.publishCarePreferenceDeleted(VEHICLE_ID);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DomainEventEnvelope> envelope = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), envelope.capture());
        assertThat(envelope.getValue().aggregateId()).isEqualTo(VEHICLE_ID);
        VehicleCarePreferenceUpdatedV1 payload =
                (VehicleCarePreferenceUpdatedV1) envelope.getValue().payload();
        assertThat(payload.deleted()).isTrue();
        assertThat(payload.serviceIntervalMonths()).isNull();
    }

    @Test
    @DisplayName("No-op when the Kafka feature flag is off (writer bean absent)")
    void noopWithoutWriter() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.publishCarePreferenceUpserted(preference());
        publisher.publishCarePreferenceDeleted(VEHICLE_ID);

        verify(entityManager, never()).flush();
        verify(writer, never()).publish(any(), any());
    }
}
