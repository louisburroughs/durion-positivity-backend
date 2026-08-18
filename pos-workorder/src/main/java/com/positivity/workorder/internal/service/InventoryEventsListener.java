package com.positivity.workorder.internal.service;

import com.positivity.domainevents.inventory.ConsumptionRecordedV1;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.PickListUpdatedV1;
import com.positivity.domainevents.inventory.PickTaskUpdatedV1;
import com.positivity.domainevents.inventory.ReservationOutcomeV1;
import com.positivity.workorder.internal.entity.ExtInventoryAvailabilityReplica;
import com.positivity.workorder.internal.entity.ExtPickListReplica;
import com.positivity.workorder.internal.entity.ExtPickTaskReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.repository.ExtInventoryAvailabilityReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPickListReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPickTaskReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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
 * Consumes {@code inventory.events.v1} into the {@code ext_pick_list} / {@code ext_pick_task}
 * replicas (ADR-0044 §6, #901) — the replacement for the retired synchronous
 * {@code InventoryPickClient} reads. {@code inventory.consumption.recorded} facts accumulate
 * consumed quantities per pick task.
 *
 * <p>Consumer contract: {@code processed_events} idempotency (owner {@code inventory}) in the
 * apply transaction, strictly-below stale guard on the fact's {@code aggregateVersion} for the
 * snapshot facts, transient DB errors rethrown for container retry/DLQ. This module processes
 * only the pick facts, yet the owner's manifest counts every fact in the window (availability,
 * on-hand, lead-time, ...), so ignored event types still record their eventIds.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class InventoryEventsListener {

    static final String OWNER = "inventory";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPickListReplicaRepository pickListReplicaRepository;
    private final ExtPickTaskReplicaRepository pickTaskReplicaRepository;
    private final ExtInventoryAvailabilityReplicaRepository availabilityReplicaRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final Counter payloadRejectedCounter;

    public InventoryEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtPickListReplicaRepository pickListReplicaRepository,
            ExtPickTaskReplicaRepository pickTaskReplicaRepository,
            ExtInventoryAvailabilityReplicaRepository availabilityReplicaRepository,
            WorkorderPartRepository workorderPartRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.pickListReplicaRepository = pickListReplicaRepository;
        this.pickTaskReplicaRepository = pickTaskReplicaRepository;
        this.availabilityReplicaRepository = availabilityReplicaRepository;
        this.workorderPartRepository = workorderPartRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "inventory")
                        .tag("entity", "inventory-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${workorder.kafka.inventory-events-topic:inventory.events.v1}",
            groupId = "${workorder.kafka.inventory-events-consumer-group:pos-workorder-inventory-events}")
    @Transactional
    public void onInventoryEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable inventory event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping inventory event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (PickListUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyPickListUpdated(envelope);
            } else if (PickTaskUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyPickTaskUpdated(envelope);
            } else if (ConsumptionRecordedV1.EVENT_TYPE.equals(eventType)) {
                applyConsumptionRecorded(envelope);
            } else if (InventoryAvailabilityUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyAvailabilityUpdated(envelope);
            } else if (ReservationOutcomeV1.EVENT_TYPE.equals(eventType)) {
                applyReservationOutcome(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring inventory event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed inventory event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed inventory event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    /**
     * Maintains the owned-availability replica behind the part-issue gate (CAP #1315).
     *
     * <p>Availability is a snapshot rather than a delta, so an out-of-order arrival is discarded
     * instead of applied: replaying an older full state would roll the replica backwards and
     * nothing would correct it until that (item, location) is touched again.
     */
    private void applyAvailabilityUpdated(JsonNode envelope) {
        InventoryAvailabilityUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), InventoryAvailabilityUpdatedV1.class);
        String rawAggregateId = envelope.path("aggregateId").stringValue(null);
        if (rawAggregateId == null || rawAggregateId.isBlank()) {
            log.warn("Skipping availability fact without aggregateId for item {}", payload.stockItemId());
            return;
        }
        UUID aggregateId = UUID.fromString(rawAggregateId);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        ExtInventoryAvailabilityReplica existing =
                availabilityReplicaRepository.findById(aggregateId).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }

        availabilityReplicaRepository.save(ExtInventoryAvailabilityReplica.builder()
                .aggregateId(aggregateId)
                .stockItemId(payload.stockItemId())
                .locationId(payload.locationId())
                .onHandQuantity(payload.onHandQuantity())
                .allocatedQuantity(payload.allocatedQuantity())
                .availableToPromiseQuantity(payload.availableToPromiseQuantity())
                .unitOfMeasure(payload.unitOfMeasure())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    /**
     * Resolves a part issued on the replica's provisional answer against what pos-inventory
     * actually decided (CAP #1315).
     *
     * <h2>An uncovered outcome reverses the issue</h2>
     *
     * This is where the work-order gate diverges from the sales-order one. A short sale is still a
     * sale, so pos-order keeps the line and marks it backordered. A short part is different: the
     * issue asserted that metal left the shelf, and the owner has now said it did not. Leaving the
     * issued quantity standing would make the shop's own records claim a technician holds a part
     * that was never there, and every consume, return and billing figure derived from it would
     * inherit that. So the issued quantity is put back and the part carries the backorder that now
     * covers it.
     *
     * <p>The reversal is floored at zero and never touches consumed or returned quantities — those
     * describe things that physically happened, and no fact from another module can un-happen them.
     *
     * <h2>Facts for other modules' lines</h2>
     *
     * The topic carries every reservation outcome, sales-order ones included. A fact naming no
     * workorder line, or one this module does not hold, belongs to someone else.
     */
    private void applyReservationOutcome(JsonNode envelope) {
        ReservationOutcomeV1 outcome = objectMapper.treeToValue(envelope.path("payload"), ReservationOutcomeV1.class);
        if (outcome.workorderLineId() == null) {
            return;
        }
        WorkorderPart part =
                workorderPartRepository.findById(outcome.workorderLineId()).orElse(null);
        if (part == null) {
            log.debug(
                    "Reservation outcome {} names workorder line {} which this module does not hold",
                    outcome.reservationId(),
                    outcome.workorderLineId());
            return;
        }

        part.setReservationId(outcome.reservationId());
        if (outcome.covered()) {
            part.setBackorderId(null);
            workorderPartRepository.save(part);
            return;
        }

        BigDecimal issued = part.getQuantityIssued() == null ? BigDecimal.ZERO : part.getQuantityIssued();
        BigDecimal reversed = issued.subtract(BigDecimal.valueOf(outcome.requiredQuantity()));
        part.setQuantityIssued(reversed.signum() < 0 ? BigDecimal.ZERO : reversed);
        part.setBackorderId(outcome.backorderId());
        workorderPartRepository.save(part);
        log.warn(
                "Reversed issue of {} on workorder part {}: pos-inventory could not cover it (backorder {})",
                outcome.requiredQuantity(),
                part.getId(),
                outcome.backorderId());
    }

    private void applyPickListUpdated(JsonNode envelope) {
        PickListUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PickListUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtPickListReplica existing =
                pickListReplicaRepository.findById(payload.pickListId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        pickListReplicaRepository.save(ExtPickListReplica.builder()
                .pickListId(payload.pickListId())
                .workorderId(payload.workorderId())
                .status(payload.status())
                .priority(payload.priority())
                .dueAt(payload.dueAt())
                .pickListCreatedAt(payload.createdAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_pick_list pickListId={} version={}", payload.pickListId(), aggregateVersion);
    }

    private void applyPickTaskUpdated(JsonNode envelope) {
        PickTaskUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PickTaskUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtPickTaskReplica existing =
                pickTaskReplicaRepository.findById(payload.pickTaskId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        // quantityConsumed is owned by consumption facts, not the task snapshot — preserve it.
        int quantityConsumed = existing == null ? 0 : existing.getQuantityConsumed();
        pickTaskReplicaRepository.save(ExtPickTaskReplica.builder()
                .pickTaskId(payload.pickTaskId())
                .pickListId(payload.pickListId())
                .workorderId(payload.workorderId())
                .skuId(payload.skuId())
                .locationId(payload.locationId())
                .quantityRequired(payload.quantityRequired())
                .quantityPicked(payload.quantityPicked())
                .quantityConsumed(quantityConsumed)
                .status(payload.status())
                .sortOrder(payload.sortOrder())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_pick_task pickTaskId={} version={}", payload.pickTaskId(), aggregateVersion);
    }

    private void applyConsumptionRecorded(JsonNode envelope) {
        ConsumptionRecordedV1 payload = objectMapper.treeToValue(envelope.path("payload"), ConsumptionRecordedV1.class);
        // Occurrence fact, applied at most once (processed_events gate above): accumulate the
        // consumed quantity per pick task. No stale guard — occurrences are not snapshots.
        for (ConsumptionRecordedV1.Line line : payload.lines()) {
            ExtPickTaskReplica task =
                    pickTaskReplicaRepository.findById(line.pickTaskId()).orElse(null);
            if (task == null) {
                log.warn(
                        "Consumption fact {} references unknown pick task {} — skipping line",
                        payload.consumptionId(),
                        line.pickTaskId());
                continue;
            }
            task.setQuantityConsumed(task.getQuantityConsumed() + Math.max(0, line.quantity()));
            task.setUpdatedAt(Instant.now(clock));
            pickTaskReplicaRepository.save(task);
        }
        log.info(
                "Applied consumption fact {} for workorder {} ({} items)",
                payload.consumptionId(),
                payload.workorderId(),
                payload.totalItemsConsumed());
    }
}
