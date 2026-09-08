package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.securityservice.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.securityservice.internal.entity.ProcessedEvent;
import com.positivity.securityservice.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.securityservice.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * Consumes {@code people.events.v1} into the {@code ext_people_staffing_assignment} read model
 * (ADR-0061 §1, #1867).
 *
 * <p>Only {@code people.staffing-assignment.updated} is applied: the fact is upserted by
 * {@code assignmentId}, guarded by the envelope's {@code aggregateVersion} (last-writer-wins; an
 * older fact never overwrites a newer row). {@code status = ENDED} marks the row ended and keeps
 * it — effective dating drives the token exp clamp (ADR-0061 §4). The assigned {@code locationId}
 * is stored verbatim, never expanded into descendants (ADR-0061 §2). A fact that narrows the
 * person's reach (see {@link StaffingAssignmentReachChange}) also revokes their live tokens via
 * {@link PersonTokenRevocationService} (ADR-0061 §4, #1874).
 *
 * <p>Other event types on the topic (e.g. {@code people.employee.updated}) are ignored but still
 * recorded in {@code processed_events}: the owner's manifest counts every fact in the window, so
 * skipping the insert would register as replica drift and trigger a pointless replay.
 * Idempotent via {@code processed_events} in the apply transaction; transient DB errors rethrow
 * for container retry/DLQ (ADR-0044 §4).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.security-service.kafka", name = "enabled", havingValue = "true")
public class PeopleEventsListener {
    private static final String PAYLOAD = "payload";
    private static final String AGGREGATE_VERSION = "aggregateVersion";

    static final String OWNER = "people";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtStaffingAssignmentReplicaRepository extStaffingAssignmentReplicaRepository;
    private final PersonTokenRevocationService personTokenRevocationService;
    private final Counter payloadRejectedCounter;

    public PeopleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtStaffingAssignmentReplicaRepository extStaffingAssignmentReplicaRepository,
            PersonTokenRevocationService personTokenRevocationService,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extStaffingAssignmentReplicaRepository = extStaffingAssignmentReplicaRepository;
        this.personTokenRevocationService = personTokenRevocationService;
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
            topics = "${pos.security-service.kafka.people-events-topic:people.events.v1}",
            groupId = "${pos.security-service.kafka.people-events-consumer-group:pos-security-people-events}")
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
            log.debug("Skipping duplicate people event eventId={}", eventId);
            return;
        }

        try {
            if (StaffingAssignmentUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyStaffingAssignmentUpdated(envelope);
            } else {
                log.debug("Ignoring people event type={}", eventType);
            }
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
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

    private void applyStaffingAssignmentUpdated(JsonNode envelope) {
        StaffingAssignmentUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path(PAYLOAD), StaffingAssignmentUpdatedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        ExtStaffingAssignmentReplica existing = extStaffingAssignmentReplicaRepository
                .findById(payload.assignmentId())
                .orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            log.debug(
                    "Ignoring stale staffing assignment fact assignmentId={} version={} < replica version={}",
                    payload.assignmentId(),
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }
        // locationId is the assigned node as pos-people recorded it — shop or parent node alike.
        // No hierarchy expansion here (ADR-0061 §2); is_primary is kept verbatim and does not
        // narrow anything (see ExtStaffingAssignmentReplica).
        extStaffingAssignmentReplicaRepository.save(ExtStaffingAssignmentReplica.builder()
                .assignmentId(payload.assignmentId())
                .personId(payload.personId())
                .locationId(payload.locationId())
                .primary(payload.primary())
                .status(payload.status())
                .effectiveFrom(payload.effectiveFrom())
                .effectiveTo(payload.effectiveTo())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Staffing assignment projection updated assignmentId={} personId={} locationId={} status={}",
                payload.assignmentId(),
                payload.personId(),
                payload.locationId(),
                payload.status());
        // ADR-0061 §4 second mechanism (#1874): a narrowing change revokes the person's live
        // tokens after the projection is updated, so a re-login sees the new reach. Widening never
        // revokes. Redis-unavailable is fail-open inside the revocation service.
        LocalDate today = LocalDate.ofInstant(Instant.now(clock), clock.getZone());
        if (StaffingAssignmentReachChange.narrows(existing, payload, today)) {
            int revoked = personTokenRevocationService.revokeLiveTokens(payload.personId());
            log.info(
                    "Staffing assignment narrowed reach; revoked live tokens personId={} assignmentId={} tokens={}",
                    payload.personId(),
                    payload.assignmentId(),
                    revoked);
        }
    }
}
