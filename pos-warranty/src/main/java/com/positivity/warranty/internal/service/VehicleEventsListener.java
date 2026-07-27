package com.positivity.warranty.internal.service;

import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.warranty.internal.entity.ExtVehicleReplica;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code vehicle.events.v1} into the {@code ext_vehicle} replica (ADR-0044 §6, #924) — the
 * event-fed replacement for the retired synchronous {@code VehicleInventoryClient.getVehicle}. Claim
 * intake reads the VIN + odometer snapshot from this replica.
 *
 * <p>Consumer contract mirrors the module's other listeners: {@code processed_events} idempotency
 * (owner {@code vehicle}) in the apply transaction, strictly-below stale guard on the fact's JPA
 * optimistic-lock {@code aggregateVersion}, transient DB errors rethrown for container retry/DLQ.
 * Unsupported event types still record their eventIds so the owner's manifest reconciles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.warranty.kafka", name = "enabled", havingValue = "true")
public class VehicleEventsListener {

    /** Producing domain, per the repo-wide processed_events convention (manifest scans key on it). */
    static final String OWNER = "vehicle";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtVehicleReplicaRepository extVehicleReplicaRepository;

    @KafkaListener(
            topics = "${pos.warranty.kafka.vehicle-events-topic:vehicle.events.v1}",
            groupId = "${pos.warranty.kafka.vehicle-events-consumer-group:pos-warranty-vehicle-events}")
    @Transactional
    public void onVehicleEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable vehicle event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping vehicle event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (VehicleUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyVehicleUpdated(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring vehicle event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed vehicle event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyVehicleUpdated(JsonNode envelope) {
        VehicleUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), VehicleUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtVehicleReplica existing =
                extVehicleReplicaRepository.findById(payload.vehicleId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extVehicleReplicaRepository.save(ExtVehicleReplica.builder()
                .vehicleId(payload.vehicleId())
                .vin(payload.vin())
                .odometerValue(payload.odometerValue())
                .odometerUnit(payload.odometerUnit())
                .active(payload.active())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_vehicle vehicleId={} version={}", payload.vehicleId(), aggregateVersion);
    }
}
