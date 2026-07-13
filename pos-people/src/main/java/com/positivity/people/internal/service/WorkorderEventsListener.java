package com.positivity.people.internal.service;

import com.positivity.domainevents.workorder.JobTimeRecordedV1;
import com.positivity.people.internal.entity.ExtJobTimeReplica;
import com.positivity.people.internal.entity.ProcessedEvent;
import com.positivity.people.internal.repository.ExtJobTimeReplicaRepository;
import com.positivity.people.internal.repository.ProcessedEventRepository;
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
 * Consumes {@code workorder.events.v1} into the {@code ext_workorder_job_time} replica
 * (ADR-0044 §6, #875), replacing the retired synchronous {@code WorkexecJobTimeClient}.
 *
 * <p>Only {@code workorder.job_time.recorded.v1} is handled; other workorder facts are ignored.
 * Idempotent via {@code processed_events} in the upsert transaction; re-completions of a
 * reopened workorder re-emit entries and the upsert (keyed by laborEntryId) is last-write-wins.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.people.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventsListener {

    static final String OWNER = "workorder";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtJobTimeReplicaRepository extJobTimeReplicaRepository;

    @KafkaListener(
            topics = "${pos.people.kafka.workorder-events-topic:workorder.events.v1}",
            groupId = "${pos.people.kafka.workorder-events-consumer-group:pos-people-workorder-events}")
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
        if (!JobTimeRecordedV1.EVENT_TYPE.equals(eventType)) {
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
            JobTimeRecordedV1 payload = objectMapper.treeToValue(envelope.path("payload"), JobTimeRecordedV1.class);
            extJobTimeReplicaRepository.save(ExtJobTimeReplica.builder()
                    .laborEntryId(payload.laborEntryId())
                    .workOrderId(payload.workOrderId())
                    .technicianId(payload.technicianId())
                    .locationId(payload.locationId())
                    .endAtUtc(payload.endAtUtc())
                    .minutes(payload.minutes())
                    .updatedAt(Instant.now(clock))
                    .build());
            log.info(
                    "Updated ext_workorder_job_time laborEntryId={} technicianId={} minutes={}",
                    payload.laborEntryId(),
                    payload.technicianId(),
                    payload.minutes());
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
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
}
