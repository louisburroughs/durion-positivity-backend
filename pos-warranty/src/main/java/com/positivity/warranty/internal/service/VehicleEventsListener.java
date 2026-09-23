package com.positivity.warranty.internal.service;

import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.warranty.internal.entity.ExtVehicleReplica;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
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
 *
 * <p>Transaction shape (#2146): the listener method is not {@code @Transactional}. The handler
 * and its {@code processed_events} mark commit together in a {@code REQUIRES_NEW} transaction of
 * their own, so a permanent failure rolls back only that work instead of leaving a shared
 * transaction rollback-only (whose commit threw, making the container retry and dead-letter the
 * record), and the failure is then recorded in a separate transaction. Transient failures still
 * propagate unrecorded for container retry; there is no at-least-once window between handler and
 * mark.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.warranty.kafka", name = "enabled", havingValue = "true")
public class VehicleEventsListener {

    /** Producing domain, per the repo-wide processed_events convention (manifest scans key on it). */
    static final String OWNER = "vehicle";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtVehicleReplicaRepository extVehicleReplicaRepository;
    private final Counter payloadRejectedCounter;

    /** The handler and its processed mark share one transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public VehicleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtVehicleReplicaRepository extVehicleReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extVehicleReplicaRepository = extVehicleReplicaRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            topics = "${pos.warranty.kafka.vehicle-events-topic:vehicle.events.v1}",
            groupId = "${pos.warranty.kafka.vehicle-events-consumer-group:pos-warranty-vehicle-events}")
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
            handlerTransaction.executeWithoutResult(_ -> {
                if (VehicleUpdatedV1.EVENT_TYPE.equals(eventType)) {
                    applyVehicleUpdated(envelope);
                } else {
                    // Ignored types still fall through to the processed_events insert below: the
                    // owner's manifest counts every fact in the window.
                    log.debug("Ignoring vehicle event type={} eventId={}", eventType, eventId);
                }
                markProcessed(eventId);
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed vehicle event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        } catch (Exception e) {
            log.warn("Skipping malformed vehicle event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        }
    }

    private void markProcessed(@NonNull String eventId) {
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
