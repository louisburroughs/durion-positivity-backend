package com.positivity.workorder.internal.service;

import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.workorder.internal.entity.ExtVehicleReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code vehicle.events.v1} into the {@code ext_vehicle} replica (#1346, ADR-0044 R3).
 *
 * <h2>Why this domain needs vehicle identity</h2>
 *
 * A fleet program identifies a vehicle by plate, VIN or its own unit number. This domain holds only
 * a {@code vehicleId} UUID on the workorder, so without this replica a fleet payment authorization
 * request cannot be built — CAP-323's canonical request refuses to be constructed without at least
 * one identifier. pos-warranty already reads the same fact for the same reason; this listener is
 * that precedent, not a new pattern.
 *
 * <p>Consumer contract mirrors the module's other listeners: {@code processed_events} idempotency
 * (owner {@code vehicle-fleetauth}) in the apply transaction, a strictly-below stale guard on
 * {@code aggregateVersion}, transient database errors rethrown for container retry.
 *
 * <p>Transaction shape (#2146): the handler and its {@code processed_events} mark commit together
 * in a transaction of their own ({@code REQUIRES_NEW}) rather than the listener's, so there is no
 * at-least-once window. A permanent failure rolls back only that work, and the failed record's mark
 * is written in a separate transaction; transient failures still propagate for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class VehicleEventsListener {

    private static final String OWNER = "vehicle-fleetauth";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtVehicleReplicaRepository vehicleReplicaRepository;

    /** One transaction for the apply, one for the processed mark; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public VehicleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtVehicleReplicaRepository vehicleReplicaRepository,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.vehicleReplicaRepository = vehicleReplicaRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${workorder.kafka.vehicle-events-topic:vehicle.events.v1}",
            groupId = "${workorder.kafka.vehicle-events-consumer-group:pos-workorder-vehicle-events}")
    public void onVehicleEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable vehicle event", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping vehicle event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            handlerTransaction.executeWithoutResult(_ -> {
                if (VehicleUpdatedV1.EVENT_TYPE.equals(eventType)) {
                    applyVehicleUpdated(envelope);
                } else {
                    // Ignored types still fall through to the processed_events insert below: the
                    // owner's manifest counts every fact in the window.
                    log.debug("Ignoring vehicle event type={} eventId={}", eventType, eventId);
                }
                processedEventRepository.save(processedMark(eventId));
            });
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries: recording this processed would leave a fleet
            // authorization request unable to name a vehicle for a reason unrelated to the vehicle.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed vehicle event eventId={}", eventId, e);
            recordFailure(eventId);
        }
    }

    /** A permanently failed record's mark, in its own transaction: the handler's rolled back. */
    private void recordFailure(String eventId) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(processedMark(eventId)));
    }

    private ProcessedEvent processedMark(String eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build();
    }

    private void applyVehicleUpdated(@NonNull JsonNode envelope) {
        VehicleUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), VehicleUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtVehicleReplica existing =
                vehicleReplicaRepository.findById(payload.vehicleId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        vehicleReplicaRepository.save(ExtVehicleReplica.builder()
                .vehicleId(payload.vehicleId())
                .vin(payload.vin())
                .licensePlate(payload.licensePlate())
                .unitNumber(payload.unitNumber())
                .odometerValue(payload.odometerValue())
                .odometerUnit(payload.odometerUnit())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_vehicle vehicleId={} version={}", payload.vehicleId(), aggregateVersion);
    }
}
