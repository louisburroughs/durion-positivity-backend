package com.positivity.people.internal.service;

import com.positivity.domainevents.security.RoleAssignmentChangedV1;
import com.positivity.people.internal.entity.ExtRoleAssignmentReplica;
import com.positivity.people.internal.entity.ProcessedEvent;
import com.positivity.people.internal.repository.ExtRoleAssignmentReplicaRepository;
import com.positivity.people.internal.repository.ProcessedEventRepository;
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
 * Consumes {@code security.events.v1} into the {@code ext_role_assignment_replica} replica
 * (ADR-0044 §6, durion#2155/#2160).
 *
 * <p>Same contract as {@link PeopleContactEventsListener}, this module's exemplar replica
 * listener, and it is deliberately a close sibling of it: idempotent via {@code processed_events}
 * inside the upsert transaction, transient DB errors rethrown so the container retries/DLQs,
 * malformed payloads logged and skipped rather than thrown. The producer's {@code aggregateVersion}
 * is a last-writer-wins hint (not a strict JPA version), so the stale guard skips only versions
 * strictly below the replica's — an equal version re-applies harmlessly because the payload is a
 * full snapshot.
 *
 * <p>Unlike the person/link replicas, there is no delete path here: {@code
 * RoleAssignmentChangedV1} carries the assignment's current state (grant <em>or</em> revoke) and
 * there is no separate "removed" event type, so a revoke is applied as an ordinary upsert with
 * {@code effectiveEndDate} / {@code revokedAt} populated. A row's lifetime spans the whole life of
 * its assignment.
 *
 * <p>An event type this listener does not handle still records a {@code processed_events} row
 * (see the {@code default ->} branch below) — the owner's reconciliation manifest counts every
 * fact in the window, so skipping the insert would read as replica drift and force a replay that
 * has nothing to repair.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.people.kafka", name = "enabled", havingValue = "true")
public class SecurityEventsListener {
    private static final String PAYLOAD = "payload";

    static final String OWNER = "security";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtRoleAssignmentReplicaRepository extRoleAssignmentReplicaRepository;
    private final Counter payloadRejectedCounter;

    public SecurityEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtRoleAssignmentReplicaRepository extRoleAssignmentReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extRoleAssignmentReplicaRepository = extRoleAssignmentReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "security-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.people.kafka.security-events-topic:security.events.v1}",
            groupId = "${pos.people.kafka.security-events-consumer-group:pos-people-security-events}")
    @Transactional
    public void onSecurityEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable security event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping security event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate security event eventId={}", eventId);
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case RoleAssignmentChangedV1.EVENT_TYPE -> applyRoleAssignmentChanged(envelope);
                default ->
                    // Ignored types still fall through to the processed_events insert below: the
                    // owner's manifest counts every fact in the window, so skipping the insert
                    // would register as replica drift and trigger a pointless replay.
                    log.debug("Ignoring security event type={}", eventType);
            }
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed security event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed security event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyRoleAssignmentChanged(JsonNode envelope) {
        RoleAssignmentChangedV1 payload =
                objectMapper.treeToValue(envelope.path(PAYLOAD), RoleAssignmentChangedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        ExtRoleAssignmentReplica existing = extRoleAssignmentReplicaRepository
                .findById(payload.assignmentId())
                .orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            log.debug(
                    "Skipping stale role assignment event assignmentId={} eventVersion={} replicaVersion={}",
                    payload.assignmentId(),
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }

        extRoleAssignmentReplicaRepository.save(ExtRoleAssignmentReplica.builder()
                .assignmentId(payload.assignmentId())
                .userId(payload.userId())
                .username(payload.username())
                .roleId(payload.roleId())
                .roleName(payload.roleName())
                .roleLocationScope(payload.roleLocationScope())
                .effectiveStartDate(payload.effectiveStartDate())
                .effectiveEndDate(payload.effectiveEndDate())
                .revokedAt(payload.revokedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_role_assignment_replica assignmentId={} username={} version={}",
                payload.assignmentId(),
                payload.username(),
                aggregateVersion);
    }
}
