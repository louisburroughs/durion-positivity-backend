package com.positivity.invoice.internal.service;

import com.positivity.domainevents.people.EmployeeUpdatedV1;
import com.positivity.invoice.internal.entity.ExtEmployeeReplica;
import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.repository.ExtEmployeeReplicaRepository;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
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
 * Consumes {@code people.events.v1} into the {@code ext_people_employee} replica
 * (ADR-0044 §6, #877). Only {@code people.employee.updated} is handled. Idempotent via
 * {@code processed_events}; strictly-below stale guard on the emission-timestamp
 * aggregateVersion; transient errors rethrown for retry/DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class PeopleEventsListener {

    static final String OWNER = "people";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtEmployeeReplicaRepository extEmployeeReplicaRepository;

    @KafkaListener(
            topics = "${pos.invoice.kafka.people-events-topic:people.events.v1}",
            groupId = "${pos.invoice.kafka.people-events-consumer-group:pos-invoice-people-events}")
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
        if (!EmployeeUpdatedV1.EVENT_TYPE.equals(eventType)) {
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
            EmployeeUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), EmployeeUpdatedV1.class);
            long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
            ExtEmployeeReplica existing =
                    extEmployeeReplicaRepository.findById(payload.employeeId()).orElse(null);
            if (existing == null || existing.getAggregateVersion() <= aggregateVersion) {
                extEmployeeReplicaRepository.save(ExtEmployeeReplica.builder()
                        .employeeId(payload.employeeId())
                        .personId(payload.personId())
                        .employeeNumber(payload.employeeNumber())
                        .status(payload.status())
                        .aggregateVersion(aggregateVersion)
                        .updatedAt(Instant.now(clock))
                        .build());
                log.info(
                        "Updated ext_people_employee employeeId={} employeeNumber={} status={}",
                        payload.employeeId(),
                        payload.employeeNumber(),
                        payload.status());
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
