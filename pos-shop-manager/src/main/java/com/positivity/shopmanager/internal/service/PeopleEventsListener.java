package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
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
 * Consumes {@code people.events.v1} into the {@code ext_people_staffing_assignment} replica
 * (ADR-0044 §6, #877). Only {@code people.staffing-assignment.updated} is handled. Idempotent
 * via {@code processed_events}; strictly-below stale guard; transient errors → retry/DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.shop-manager.kafka", name = "enabled", havingValue = "true")
public class PeopleEventsListener {

    static final String OWNER = "people";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository;

    @KafkaListener(
            topics = "${pos.shop-manager.kafka.people-events-topic:people.events.v1}",
            groupId = "${pos.shop-manager.kafka.people-events-consumer-group:pos-shop-manager-people-events}")
    @Transactional
    public void onPeopleEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable people event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (!StaffingAssignmentUpdatedV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring people event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping people event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            StaffingAssignmentUpdatedV1 payload =
                    objectMapper.treeToValue(envelope.path("payload"), StaffingAssignmentUpdatedV1.class);
            long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
            ExtStaffingAssignmentReplica existing =
                    assignmentReplicaRepository.findById(payload.assignmentId()).orElse(null);
            if (existing == null || existing.getAggregateVersion() <= aggregateVersion) {
                assignmentReplicaRepository.save(ExtStaffingAssignmentReplica.builder()
                        .assignmentId(payload.assignmentId())
                        .employeeId(payload.employeeId())
                        .personId(payload.personId())
                        .locationId(payload.locationId())
                        .role(payload.role())
                        .primary(payload.primary())
                        .status(payload.status())
                        .effectiveFrom(payload.effectiveFrom())
                        .effectiveTo(payload.effectiveTo())
                        .aggregateVersion(aggregateVersion)
                        .updatedAt(Instant.now(clock))
                        .build());
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed people event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }
}
