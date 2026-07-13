package com.positivity.inventory.internal.service;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.inventory.internal.entity.ExtWorkorderPartReplica;
import com.positivity.inventory.internal.entity.ExtWorkorderReplica;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.repository.ExtWorkorderPartReplicaRepository;
import com.positivity.inventory.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * Consumes {@code workorder.events.v1} into the {@code ext_workorder} / {@code ext_workorder_part}
 * replicas (ADR-0044 §6, #897), replacing the retired synchronous
 * {@code WorkorderValidationClient} line lookup.
 *
 * <p>Phase 3.4 consumer contract: {@code processed_events} idempotency in the apply transaction,
 * strictly-below stale guard on the emission-timestamp {@code aggregateVersion}, transient DB
 * errors rethrown for container retry/DLQ. The topic carries many workorder fact types (job time,
 * sessions, estimates) this module ignores — their eventIds are still recorded so the owner's
 * manifest reconciles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.inventory.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventsListener {

    static final String OWNER = "workorder";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtWorkorderReplicaRepository extWorkorderReplicaRepository;
    private final ExtWorkorderPartReplicaRepository extWorkorderPartReplicaRepository;

    @KafkaListener(
            topics = "${pos.inventory.kafka.workorder-events-topic:workorder.events.v1}",
            groupId = "${pos.inventory.kafka.workorder-events-consumer-group:pos-inventory-workorder-events}")
    @Transactional
    public void onWorkorderEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable workorder event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping workorder event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (WorkorderUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyWorkorderUpdated(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring workorder event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed workorder event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyWorkorderUpdated(JsonNode envelope) {
        WorkorderUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), WorkorderUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtWorkorderReplica existing =
                extWorkorderReplicaRepository.findById(payload.workorderId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extWorkorderReplicaRepository.save(ExtWorkorderReplica.builder()
                .workorderId(payload.workorderId())
                .workorderNumber(payload.workorderNumber())
                .status(payload.status())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());

        // The fact carries the full part-line set — replace, don't merge.
        List<WorkorderUpdatedV1.PartLine> parts = payload.parts();
        if (parts != null) {
            extWorkorderPartReplicaRepository.deleteByWorkorderId(payload.workorderId());
            parts.forEach(line -> extWorkorderPartReplicaRepository.save(ExtWorkorderPartReplica.builder()
                    .workorderLineId(line.workorderLineId())
                    .workorderId(payload.workorderId())
                    .productEntityId(line.productEntityId())
                    .quantity(line.quantity())
                    .build()));
        }
        log.info("Updated ext_workorder workorderId={} version={}", payload.workorderId(), aggregateVersion);
    }
}
