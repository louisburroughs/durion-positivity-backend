package com.positivity.order.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.order.internal.entity.ExtVehicle;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtVehicleRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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
 * Consumes {@code vehicle.events.v1} into the {@code ext_vehicle} replica (ADR-0044 §6, parity
 * story I2 replica redesign). {@code accountId} is the owning customer party; the replica powers
 * vehicle existence + ownership validation in {@code ReplicaCustomerPortAdapter}.
 *
 * <p>The stale guard on the fact's {@code aggregateVersion} is {@link ReplicaVersionGuard}
 * (#1486): pos-vehicle-inventory's {@code VehicleEventPublisher} flushes a JPA {@code @Version}
 * before emit, so the version strictly advances — a held row is stale only when its version is
 * strictly greater than the incoming fact's. An equal version applies, both as an idempotent no-op
 * for live traffic and because {@code POST .../facts/replay} depends on it to repair a replica that
 * holds the version number but wrong or missing rows.
 *
 * <p>Transaction shape (#2146): the listener method is not {@code @Transactional}; the handler
 * and its {@code processed_events} mark run together in a {@code REQUIRES_NEW} transaction of
 * their own. A permanent failure thrown through a transactional repository or service therefore
 * rolls back only that work and is logged and skipped, rather than marking a listener-wide
 * transaction rollback-only, whose commit would throw {@code UnexpectedRollbackException} and
 * send the record through the container's retry and dead-letter ladder. Transient database errors
 * still propagate for container retry, and since the mark commits with the work there is no
 * window in which one lands without the other.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class VehicleEventsListener {

    static final String OWNER = "vehicle";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtVehicleRepository extVehicleRepository;

    /** The event's handler work and its processed mark, in a transaction of their own; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public VehicleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtVehicleRepository extVehicleRepository,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extVehicleRepository = extVehicleRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.order.kafka.vehicle-events-topic:vehicle.events.v1}",
            groupId = "${pos.order.kafka.vehicle-events-consumer-group:pos-order-vehicle-events}")
    public void onVehicleEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable vehicle event: {}", message, e);
            return;
        }
        if (!VehicleUpdatedV1.EVENT_TYPE.equals(envelope.path("eventType").stringValue(null))) {
            return;
        }
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
                applyUpdate(envelope);
                processedEventRepository.save(ProcessedEvent.builder()
                        .eventId(eventId)
                        .owner(OWNER)
                        .processedAt(Instant.now(clock))
                        .build());
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed vehicle event eventId={}", eventId, e);
        }
    }

    private void applyUpdate(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID vehicleId = UUID.fromString(payload.path("vehicleId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        ExtVehicle existing = extVehicleRepository.findById(vehicleId).orElse(null);
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — the
        // publisher's aggregateVersion strictly advances, so equal means identical content, and
        // replay resends the held version deliberately to repair wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            return;
        }
        ExtVehicle replica = existing != null ? existing : new ExtVehicle();
        replica.setVehicleId(vehicleId);
        replica.setAccountId(UUID.fromString(payload.path("accountId").stringValue(null)));
        replica.setActive(payload.path("active").booleanValue(true));
        replica.setAggregateVersion(aggregateVersion);
        replica.setSyncedAt(Instant.now(clock));
        extVehicleRepository.save(replica);
    }
}
