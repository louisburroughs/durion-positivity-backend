package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.entity.ServiceHistory;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import com.positivity.domainevents.workorder.WorkorderServiceCompletedV1;
import com.positivity.domainevents.workorder.WorkorderServiceLineDeclinedV1;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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
 * Consumes {@code workorder.events.v1} for the CRM service-history feed (FI-3, #1133, resolves spec
 * O-4 + O-7).
 *
 * <p>pos-workorder owns the workorder facts (ADR-0044 R6); this listener projects two of them into
 * CRM-local read models:
 *
 * <ul>
 *   <li>{@code workorder.service.completed.v1} → a {@link ServiceHistory} row, powering
 *       last-service-age and service-due segment predicates.
 *   <li>{@code workorder.service-line.declined.v1} → exactly one {@code DECLINED_SERVICE_FOLLOWUP}
 *       {@link FollowUpTask}, and the declined-service segment predicate.
 * </ul>
 *
 * <p>Same contract as {@link VehicleEventsListener}: idempotent via {@code processed_events} in the
 * write transaction (a per-write {@code source_event_id} unique guard backs it up), transient DB
 * errors rethrown for container retry/DLQ, malformed payloads logged and skipped. All other
 * workorder fact types on the topic are ignored.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventsListener {

    static final String OWNER = "workorder";
    private static final String SYSTEM_ACTOR = "workorder-events";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ServiceHistoryRepository serviceHistoryRepository;
    private final FollowUpTaskRepository followUpTaskRepository;

    @KafkaListener(
            topics = "${pos.customer.kafka.workorder-facts-topic:workorder.events.v1}",
            groupId = "${pos.customer.kafka.workorder-facts-consumer-group:pos-customer-workorder-facts}")
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
        boolean handled = WorkorderServiceCompletedV1.EVENT_TYPE.equals(eventType)
                || WorkorderServiceLineDeclinedV1.EVENT_TYPE.equals(eventType);
        if (!handled) {
            log.debug("Ignoring workorder event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping workorder event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate workorder event eventId={}", eventId);
            return;
        }

        try {
            if (WorkorderServiceCompletedV1.EVENT_TYPE.equals(eventType)) {
                applyServiceCompleted(envelope, eventId);
            } else {
                applyServiceLineDeclined(envelope, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed workorder event eventId={} type={}", eventId, eventType, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyServiceCompleted(JsonNode envelope, String eventId) {
        WorkorderServiceCompletedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), WorkorderServiceCompletedV1.class);
        if (payload.partyId() == null) {
            log.debug("Workorder {} completion has no party; no service-history row", payload.workorderId());
            return;
        }
        if (serviceHistoryRepository.existsBySourceEventId(eventId)) {
            log.debug("Service-history row already exists for eventId={}", eventId);
            return;
        }
        serviceHistoryRepository.save(ServiceHistory.builder()
                .partyId(payload.partyId())
                .vehicleId(payload.vehicleId())
                .sourceWorkorderId(payload.workorderId())
                .workorderNumber(payload.workorderNumber())
                .completedAt(payload.completedAt())
                .amount(payload.totalAmount())
                .sourceEventId(eventId)
                .createdAt(Instant.now(clock))
                .build());
        log.info(
                "Projected service_history partyId={} workorderId={} amount={}",
                payload.partyId(),
                payload.workorderId(),
                payload.totalAmount());
    }

    private void applyServiceLineDeclined(JsonNode envelope, String eventId) {
        WorkorderServiceLineDeclinedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), WorkorderServiceLineDeclinedV1.class);
        if (payload.partyId() == null) {
            log.debug("Declined service line {} has no party; no follow-up task", payload.workorderLineId());
            return;
        }
        // Exactly one follow-up per originating fact (spec §6.1): the unique source_event_id backs
        // this up, but checking first avoids a constraint-violation round-trip on redelivery.
        if (followUpTaskRepository.existsBySourceEventId(eventId)) {
            log.debug("Declined-service follow-up already exists for eventId={}", eventId);
            return;
        }
        UUID workorderId = payload.workorderId();
        followUpTaskRepository.save(FollowUpTask.builder()
                .partyId(payload.partyId())
                .vehicleId(payload.vehicleId())
                .type(FollowUpType.DECLINED_SERVICE_FOLLOWUP)
                .status(FollowUpStatus.OPEN)
                .sourceWorkorderId(workorderId.toString())
                .sourceEventId(eventId)
                .reason(payload.description())
                .notes(payload.declineReason())
                .createdBy(SYSTEM_ACTOR)
                .build());
        log.info(
                "Created DECLINED_SERVICE_FOLLOWUP partyId={} workorderId={} line={}",
                payload.partyId(),
                workorderId,
                payload.workorderLineId());
    }
}
