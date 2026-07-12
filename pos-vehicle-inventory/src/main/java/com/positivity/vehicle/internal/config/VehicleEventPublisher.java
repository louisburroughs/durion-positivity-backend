package com.positivity.vehicle.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Emits {@code vehicle.vehicle.updated} to the vehicle outbox after registry mutations
 * (ADR-0044 §6, #843).
 *
 * <p>No-op when the Kafka feature flag ({@code pos.vehicle-inventory.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 */
@Slf4j
@Component
public class VehicleEventPublisher {

    private final Clock clock;
    private final EntityManager entityManager;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final String eventsTopic;

    public VehicleEventPublisher(
            Clock clock,
            EntityManager entityManager,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            // Same property ManifestPublisher scans event_outbox by, so an override can never
            // desync the outbox rows from the manifest computation (PR #865 review).
            @Value("${pos.vehicle-inventory.kafka.events-topic:vehicle.events.v1}") String eventsTopic) {
        this.clock = clock;
        this.entityManager = entityManager;
        this.outboxEventWriter = outboxEventWriter;
        this.eventsTopic = eventsTopic;
    }

    public void publishVehicleUpdated(@NonNull VehicleRecord vehicle) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Flush so the JPA @Version already carries the committed value: versions are then
        // strictly increasing per vehicle (create=0, updates=1,2,...), which the consumer's
        // stale-event guard relies on.
        entityManager.flush();
        VehicleUpdatedV1 payload = new VehicleUpdatedV1(
                vehicle.getVehicleId(),
                vehicle.getAccountId(),
                vehicle.getVin(),
                vehicle.getVinNormalized(),
                vehicle.getUnitNumber(),
                vehicle.getDescription(),
                vehicle.getLicensePlate(),
                vehicle.getLicensePlateJurisdiction(),
                vehicle.getYear(),
                vehicle.getMake(),
                vehicle.getModel(),
                vehicle.getTrim(),
                Boolean.TRUE.equals(vehicle.getIsActive()),
                vehicle.getCreatedAt(),
                vehicle.getUpdatedAt());
        DomainEventEnvelope<VehicleUpdatedV1> envelope = DomainEventEnvelope.of(
                VehicleUpdatedV1.EVENT_TYPE,
                VehicleUpdatedV1.SCHEMA_VERSION,
                vehicle.getVehicleId(),
                vehicle.getVersion() == null ? 0L : vehicle.getVersion().longValue(),
                "pos-vehicle-inventory",
                null,
                null,
                payload,
                clock);
        writer.publish(eventsTopic, envelope);
        log.debug(
                "Queued vehicle.vehicle.updated vehicleId={} accountId={} active={}",
                vehicle.getVehicleId(),
                vehicle.getAccountId(),
                vehicle.getIsActive());
    }
}
