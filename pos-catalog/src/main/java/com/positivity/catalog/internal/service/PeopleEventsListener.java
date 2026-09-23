package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.ExtSkillReplica;
import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.repository.ExtSkillReplicaRepository;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.people.SkillUpdatedV1;
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
 * Consumes {@code people.events.v1} for the one fact this module needs from the People domain:
 * the skill registry ({@code people.skill.updated}, CAP-329), mirrored into {@code ext_skill} so a
 * service's skill requirement is validated against a replica rather than a synchronous call
 * (ADR-0044 §6). Every other people fact on the topic is acknowledged and ignored.
 *
 * <p>Same shape as {@link LocationEventsListener}: idempotent on eventId through {@code
 * processed_events}, stale snapshots dropped by aggregate version, malformed payloads counted
 * and skipped rather than retried.
 *
 * <p><b>Transaction shape (#2146).</b> The skill upsert and its {@code processed_events} mark
 * commit together in their own {@code REQUIRES_NEW} transaction, so a permanent failure rolls back
 * only that work and is recorded in a separate transaction; there is no window between an applied
 * fact and its mark. Transient failures still propagate for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.catalog.kafka", name = "enabled", havingValue = "true")
public class PeopleEventsListener {

    static final String OWNER = "people";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtSkillReplicaRepository skillReplicaRepository;
    private final Counter payloadRejectedCounter;

    /** The apply and its processed mark in one transaction; a failure's mark in its own. */
    private final TransactionTemplate handlerTransaction;

    public PeopleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtSkillReplicaRepository skillReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.skillReplicaRepository = skillReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. a malformed identifier)")
                        .tag("owner", OWNER)
                        .tag("entity", "people-events")
                        .register(registry);
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.catalog.kafka.people-events-topic:people.events.v1}",
            groupId = "${pos.catalog.kafka.people-events-consumer-group:pos-catalog-people-events}")
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
            handlerTransaction.executeWithoutResult(_ -> {
                switch (eventType == null ? "" : eventType) {
                    case SkillUpdatedV1.EVENT_TYPE -> applySkillUpdated(envelope, eventId);
                    default -> log.debug("Ignoring people event type={} eventId={}", eventType, eventId);
                }
                recordProcessed(eventId, OWNER);
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed people event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId, OWNER));
        } catch (Exception e) {
            log.warn("Skipping malformed people event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId, OWNER));
        }
    }

    /**
     * Upsert by skill id under the stale guard. A retirement ({@code active=false}) is applied
     * like any other change: the row stays, so a requirement that still names the skill can be
     * told the vocabulary moved rather than that the skill never existed.
     */

    /** Records the eventId as processed, inside whichever transaction the caller runs. */
    private void recordProcessed(@NonNull String eventId, @NonNull String owner) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(owner)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applySkillUpdated(@NonNull JsonNode envelope, @NonNull String eventId) throws DatabindException {
        SkillUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), SkillUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtSkillReplica existing =
                skillReplicaRepository.findById(payload.skillId()).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Ignoring stale skill event skillId={} version={} eventId={}",
                    payload.skillId(),
                    aggregateVersion,
                    eventId);
            return;
        }
        skillReplicaRepository.save(ExtSkillReplica.builder()
                .skillId(payload.skillId())
                .code(payload.code())
                .name(payload.name())
                .competenceCode(payload.competenceCode())
                .minGvwrClass(payload.minGvwrClass())
                .maxGvwrClass(payload.maxGvwrClass())
                .active(payload.active())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }
}
