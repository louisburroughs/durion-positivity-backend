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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code vehicle.events.v1} into the {@code ext_vehicle} replica (ADR-0044 §6, #891 —
 * the Phase 2 #843 deferral for this module). Only {@code vehicle.vehicle.updated} exists;
 * deactivation arrives as {@code active=false}. Idempotent via {@code processed_events};
 * strictly-below stale guard on the aggregateVersion (the owner's JPA optimistic-lock version);
 * transient errors rethrown for retry/DLQ.
 *
 * <p><strong>Transaction shape (#2146).</strong> The listener method is not {@code @Transactional}:
 * the handler's work and the {@code processed_events} mark commit together in a
 * {@code REQUIRES_NEW} transaction of their own, so neither lands without the other. A permanent
 * failure rolls back only that work and is recorded in a separate transaction, instead of
 * poisoning a shared transaction whose commit then throws and sends the record through retry and
 * dead-lettering. Transient database errors still propagate, unrecorded, for container retry.
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

    /** Runs the handler with its processed mark, and records a failure; see the class doc. */
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
            topics = "${pos.shop-manager.kafka.vehicle-events-topic:vehicle.events.v1}",
            groupId = "${pos.shop-manager.kafka.vehicle-events-consumer-group:pos-shop-manager-vehicle-events}")
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
                    log.debug("Ignoring vehicle event type={} eventId={}", eventType, eventId);
                }
                processedEventRepository.save(processedMark(eventId));
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed vehicle event payload eventId={}: {}", eventId, e.getMessage(), e);
            recordFailed(eventId);
        } catch (Exception e) {
            log.warn("Skipping malformed vehicle event eventId={}", eventId, e);
            recordFailed(eventId);
        }
    }

    private ProcessedEvent processedMark(String eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build();
    }

    /** Records a permanently failed event in a transaction of its own; see the class doc. */
    private void recordFailed(String eventId) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(processedMark(eventId)));
    }

    private void applyVehicleUpdated(JsonNode envelope) {
        JsonNode payloadNode = envelope.path("payload");
        VehicleUpdatedV1 payload = objectMapper.treeToValue(payloadNode, VehicleUpdatedV1.class);
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
                // gvwrClass arrived after this listener started running (CAP-327): a fact from a
                // pre-change producer has no such field, which must not clear a class already
                // replicated during a rolling deploy or a replay of an older stored event.
                .gvwrClass(
                        payloadNode.has("gvwrClass")
                                ? payload.gvwrClass()
                                : existing == null ? null : existing.getGvwrClass())
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
