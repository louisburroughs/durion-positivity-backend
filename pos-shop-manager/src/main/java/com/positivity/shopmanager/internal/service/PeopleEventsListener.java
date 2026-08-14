package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
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
 * Consumes {@code people.events.v1} into the {@code ext_people_staffing_assignment} replica
 * (ADR-0044 §6, #877). Only {@code people.staffing-assignment.updated} is handled. Idempotent
 * via {@code processed_events}; strictly-below stale guard; transient errors → retry/DLQ.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.shop-manager.kafka", name = "enabled", havingValue = "true")
public class PeopleEventsListener {

    static final String OWNER = "people";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository;
    private final Counter payloadRejectedCounter;

    public PeopleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.assignmentReplicaRepository = assignmentReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "people")
                        .tag("entity", "people-events")
                        .register(registry);
    }

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
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed people event payload eventId={}: {}", eventId, e.getMessage(), e);
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
