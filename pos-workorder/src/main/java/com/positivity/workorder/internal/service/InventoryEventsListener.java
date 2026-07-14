package com.positivity.workorder.internal.service;

import com.positivity.domainevents.inventory.ConsumptionRecordedV1;
import com.positivity.domainevents.inventory.PickListUpdatedV1;
import com.positivity.domainevents.inventory.PickTaskUpdatedV1;
import com.positivity.workorder.internal.entity.ExtPickListReplica;
import com.positivity.workorder.internal.entity.ExtPickTaskReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtPickListReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPickTaskReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
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
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class InventoryEventsListener {

    static final String OWNER = "inventory";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPickListReplicaRepository pickListReplicaRepository;
    private final ExtPickTaskReplicaRepository pickTaskReplicaRepository;

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
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring inventory event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed inventory event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
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
