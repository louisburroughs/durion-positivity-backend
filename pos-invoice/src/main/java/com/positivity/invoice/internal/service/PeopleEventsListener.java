package com.positivity.invoice.internal.service;

import com.positivity.domainevents.people.EmployeeUpdatedV1;
import com.positivity.invoice.internal.entity.ExtEmployeeReplica;
import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.repository.ExtEmployeeReplicaRepository;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
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
 * Consumes {@code people.events.v1} into the {@code ext_people_employee} replica
 * (ADR-0044 §6, #877). Only {@code people.employee.updated} updates the replica;
 * {@code people.staffing-assignment.updated} and any other type on the topic are ignored for
 * replication purposes but still recorded — see {@link #onPeopleEvent}. Idempotent via
 * {@code processed_events}; strictly-below stale guard on the emission-timestamp
 * aggregateVersion; transient errors rethrown for retry/DLQ.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class PeopleEventsListener {

    static final String OWNER = "people";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtEmployeeReplicaRepository extEmployeeReplicaRepository;
    private final Counter payloadRejectedCounter;

    public PeopleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtEmployeeReplicaRepository extEmployeeReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extEmployeeReplicaRepository = extEmployeeReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "people-events")
                        .register(registry);
    }

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
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping people event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case EmployeeUpdatedV1.EVENT_TYPE -> applyEmployeeUpdated(envelope);
                default ->
                    // Ignored types (e.g. people.staffing-assignment.updated) still fall through
                    // to the processed_events insert below: the owner's manifest counts every
                    // fact in the window, so skipping the insert would register as replica
                    // drift and trigger a pointless replay.
                    log.debug("Ignoring people event type={} eventId={}", eventType, eventId);
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

    private void applyEmployeeUpdated(@NonNull JsonNode envelope) {
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
    }
}
