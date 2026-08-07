package com.positivity.vehicle.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.vehicle.VehicleCarePreferenceUpdatedV1;
import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Emits {@code vehicle.care-preference.updated} to the vehicle outbox after care-preference
 * mutations (#1175), mirroring {@link VehicleEventPublisher}.
 *
 * <p>No-op when the Kafka feature flag ({@code pos.vehicle-inventory.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 *
 * <p>The envelope's {@code aggregateId} is the vehicle id, keeping care-preference facts on the
 * same Kafka partition (and in order with) the vehicle's registry facts. {@code aggregateVersion}
 * is a last-writer-wins epoch-millis stamp, <em>not</em> the row's JPA {@code @Version}: care
 * preferences are hard-deleted and may be re-created, which would restart a row version at 0 and
 * wedge a version-based consumer stale guard. Consumers order these facts with a {@code >} guard
 * (the people-contact replica precedent).
 */
@Slf4j
@Component
public class CarePreferenceEventPublisher {

    private final Clock clock;
    private final EntityManager entityManager;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final String eventsTopic;

    public CarePreferenceEventPublisher(
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

    /** Queue the fact for a created or updated care preference. */
    public void publishCarePreferenceUpserted(@NonNull VehicleCarePreference preference) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Flush so auditing (@LastModifiedDate) has stamped the row before we snapshot it.
        entityManager.flush();
        UUID vehicleId = preference.getVehicleId();
        VehicleCarePreferenceUpdatedV1 payload = new VehicleCarePreferenceUpdatedV1(
                vehicleId, preference.getServiceIntervalMonths(), false, preference.getUpdatedAt());
        publish(writer, vehicleId, payload);
        log.debug(
                "Queued vehicle.care-preference.updated vehicleId={} serviceIntervalMonths={}",
                vehicleId,
                preference.getServiceIntervalMonths());
    }

    /** Queue the tombstone fact for a deleted care preference. */
    public void publishCarePreferenceDeleted(@NonNull UUID vehicleId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        publish(writer, vehicleId, new VehicleCarePreferenceUpdatedV1(vehicleId, null, true, null));
        log.debug("Queued vehicle.care-preference.updated tombstone vehicleId={}", vehicleId);
    }

    private void publish(OutboxEventWriter writer, UUID vehicleId, VehicleCarePreferenceUpdatedV1 payload) {
        DomainEventEnvelope<VehicleCarePreferenceUpdatedV1> envelope = DomainEventEnvelope.of(
                VehicleCarePreferenceUpdatedV1.EVENT_TYPE,
                VehicleCarePreferenceUpdatedV1.SCHEMA_VERSION,
                vehicleId,
                Instant.now(clock).toEpochMilli(),
                "pos-vehicle-inventory",
                null,
                null,
                payload,
                clock);
        writer.publish(eventsTopic, envelope);
    }
}
