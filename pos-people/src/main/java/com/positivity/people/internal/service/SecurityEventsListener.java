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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
 *
 * <p>Transaction shape (#2146): the listener method is not {@code @Transactional}. The handler
 * and its {@code processed_events} mark commit together in a {@code REQUIRES_NEW} transaction of
 * their own, so a permanent failure rolls back only that work instead of leaving a shared
 * transaction rollback-only (whose commit threw, making the container retry and dead-letter the
 * record). A rejected payload stays unrecorded, as described below; transient failures still
 * propagate unrecorded for container retry; there is no at-least-once window between handler and
 * mark.
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

    /** The handler and its processed mark share one transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public SecurityEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtRoleAssignmentReplicaRepository extRoleAssignmentReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extRoleAssignmentReplicaRepository = extRoleAssignmentReplicaRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            handlerTransaction.executeWithoutResult(_ -> {
                switch (eventType == null ? "" : eventType) {
                    case RoleAssignmentChangedV1.EVENT_TYPE -> applyRoleAssignmentChanged(envelope);
                    default ->
                        // Ignored types still fall through to the processed_events insert below: the
                        // owner's manifest counts every fact in the window, so skipping the insert
                        // would register as replica drift and trigger a pointless replay. A *rejected*
                        // payload is the opposite case and is caught below without recording: there the
                        // replica really is missing the fact, so the drift is genuine and the replay it
                        // provokes is the repair, not a false alarm. Recording it instead would make
                        // existsById skip the event forever, putting it beyond the reach of any replay
                        // -- which is how a role-assignment fact published before a field was added to
                        // the contract would be lost permanently rather than merely deferred.
                        log.debug("Ignoring security event type={}", eventType);
                }
                markProcessed(eventId);
            });
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
    }

    private void markProcessed(@NonNull String eventId) {
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
