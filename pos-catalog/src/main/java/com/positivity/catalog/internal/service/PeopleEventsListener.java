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
import org.springframework.transaction.annotation.Transactional;
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

    public PeopleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtSkillReplicaRepository skillReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
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
    }

    @KafkaListener(
            topics = "${pos.catalog.kafka.people-events-topic:people.events.v1}",
            groupId = "${pos.catalog.kafka.people-events-consumer-group:pos-catalog-people-events}")
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
                case SkillUpdatedV1.EVENT_TYPE -> applySkillUpdated(envelope, eventId);
                default -> log.debug("Ignoring people event type={} eventId={}", eventType, eventId);
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

    /**
     * Upsert by skill id under the stale guard. A retirement ({@code active=false}) is applied
     * like any other change: the row stays, so a requirement that still names the skill can be
     * told the vocabulary moved rather than that the skill never existed.
     */
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
