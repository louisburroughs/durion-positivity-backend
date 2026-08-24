package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtVehicleReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code vehicle.events.v1} into the {@code ext_vehicle} replica (ADR-0044 §6, #891 —
 * the Phase 2 #843 deferral for this module). Only {@code vehicle.vehicle.updated} exists;
 * deactivation arrives as {@code active=false}. Idempotent via {@code processed_events};
 * strictly-below stale guard on the aggregateVersion (the owner's JPA optimistic-lock version);
 * transient errors rethrown for retry/DLQ.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.shop-manager.kafka", name = "enabled", havingValue = "true")
public class VehicleEventsListener {

    static final String OWNER = "vehicle";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtVehicleReplicaRepository extVehicleReplicaRepository;
    private final Counter payloadRejectedCounter;

    public VehicleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtVehicleReplicaRepository extVehicleReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extVehicleReplicaRepository = extVehicleReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "vehicle-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.shop-manager.kafka.vehicle-events-topic:vehicle.events.v1}",
            groupId = "${pos.shop-manager.kafka.vehicle-events-consumer-group:pos-shop-manager-vehicle-events}")
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
                log.debug("Ignoring vehicle event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed vehicle event payload eventId={}: {}", eventId, e.getMessage(), e);
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
                .accountId(payload.accountId())
                .vin(payload.vin())
                .unitNumber(payload.unitNumber())
                .description(payload.description())
                .licensePlate(payload.licensePlate())
                .year(payload.year())
                .make(payload.make())
                .model(payload.model())
                .active(payload.active())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_vehicle vehicleId={} accountId={} version={}",
                payload.vehicleId(),
                payload.accountId(),
                aggregateVersion);
    }
}
