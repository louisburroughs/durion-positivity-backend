package com.positivity.vehicle.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link VehicleEventPublisher} (ADR-0044 §6, #843).
 */
class VehicleEventPublisherTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final EntityManager entityManager = mock(EntityManager.class);
    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private VehicleEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new VehicleEventPublisher(TEST_CLOCK, entityManager, writerProvider);
    }

    private VehicleRecord vehicle() {
        return VehicleRecord.builder()
                .vehicleId(VEHICLE_ID)
                .accountId(ACCOUNT_ID)
                .vin("1HGBH41JXMN109186")
                .vinNormalized("1HGBH41JXMN109186")
                .unitNumber("UNIT-1")
                .description("2022 Honda Accord")
                .year(2022)
                .make("Honda")
                .model("Accord")
                .isActive(true)
                .version(4L)
                .build();
    }

    @Test
    @DisplayName("Queues a vehicle.vehicle.updated envelope on vehicle.events.v1 with the JPA version")
    void queuesEnvelope() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);

        publisher.publishVehicleUpdated(vehicle());

        // The @Version value must already be committed when the payload is built.
        verify(entityManager).flush();
        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DomainEventEnvelope> envelope = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(topic.capture(), envelope.capture());
        assertThat(topic.getValue()).isEqualTo("vehicle.events.v1");
        assertThat(envelope.getValue().eventType()).isEqualTo(VehicleUpdatedV1.EVENT_TYPE);
        assertThat(envelope.getValue().aggregateId()).isEqualTo(VEHICLE_ID);
        assertThat(envelope.getValue().aggregateVersion()).isEqualTo(4L);
        assertThat(envelope.getValue().sourceService()).isEqualTo("pos-vehicle-inventory");
        VehicleUpdatedV1 payload = (VehicleUpdatedV1) envelope.getValue().payload();
        assertThat(payload.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(payload.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(payload.vin()).isEqualTo("1HGBH41JXMN109186");
        assertThat(payload.active()).isTrue();
    }

    @Test
    @DisplayName("No-op when the Kafka feature flag is off (writer bean absent)")
    void noopWithoutWriter() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.publishVehicleUpdated(vehicle());

        verify(entityManager, never()).flush();
        verify(writer, never()).publish(any(), any());
    }
}
