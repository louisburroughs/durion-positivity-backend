package com.positivity.workorder.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional-outbox writer for workorder domain events (ADR-0044 §4).
 *
 * <p>Serializes a full {@link DomainEventEnvelope} into {@code event_outbox} within the caller's
 * transaction, so an event exists if and only if the business state change committed.
 * {@link OutboxPublisher} drains the table to Kafka with at-least-once delivery.
 *
 * <p>This module used to hand-build the envelope as a map, because {@link DomainEventEnvelope}
 * rejects underscores in the event type and several of this module's types contained them. Those
 * types were renamed to the hyphenated form (#1286), so the envelope is now the shared one and the
 * schema version it requires is supplied by the payload type rather than a literal (#1279).
 *
 * <p>Unlike its peers this writer keeps a convenience signature rather than taking a ready-made
 * envelope: the callers are transactional relays that know their payload and aggregate id but not
 * the topic, and building the nine-argument envelope at each of them would duplicate the same
 * source-service and clock wiring thirteen times.
 *
 * <p>Two {@code publish} overloads exist for two different {@code aggregateVersion} contracts
 * (#1486). The four-argument form stamps {@code Instant.now(clock).toEpochMilli()} — an
 * emission-timestamp LWW hint, fine for facts nothing version-guards, but two mutations landing in
 * the same millisecond tie under it. The five-argument form takes an explicit
 * {@code aggregateVersion} and MUST be used for facts about a version-guarded aggregate: as of
 * #1486 that is {@code WorkorderUpdatedV1} and {@code EstimateUpdatedV1}, fed from
 * {@code Workorder}/{@code Estimate}'s flushed JPA {@code @Version}, which strictly increments on
 * every committed mutation and so can never tie.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class OutboxEventWriter {
    private static final String SOURCE_SERVICE = "pos-workorder";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;

    @Value("${workorder.kafka.events-topic:workorder.events.v1}")
    private String eventsTopic;

    /**
     * Queue a domain event for publication as part of the current transaction. Must be called
     * inside the business transaction ({@code MANDATORY}) — that is the whole point of the outbox.
     *
     * <p>{@code schemaVersion} must come from a constant on the payload type rather than a literal
     * at the call site, so the number moves with the payload it describes (#1279). Payloads that
     * have a {@code pos-domain-events} record pass that record's {@code SCHEMA_VERSION}; the
     * module-internal payload types declare their own.
     *
     * <p>Stamps {@code Instant.now(clock).toEpochMilli()} as {@code aggregateVersion} — an
     * emission-timestamp LWW hint (ADR-0044 §6, #897), fine for a fact nothing version-guards, but
     * not strictly-advancing: two mutations landing in the same millisecond tie. A fact about a
     * version-guarded aggregate ({@code WorkorderUpdatedV1}, {@code EstimateUpdatedV1} as of #1486)
     * must use {@link #publish(String, int, UUID, long, Object)} instead, fed from the entity's
     * flushed {@code @Version}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(
            @NonNull String eventType, int schemaVersion, @NonNull UUID aggregateId, @NonNull Object payload) {
        publish(eventType, schemaVersion, aggregateId, Instant.now(clock).toEpochMilli(), payload);
    }

    /**
     * Queue a domain event for publication with an explicit {@code aggregateVersion}, as part of
     * the current transaction. Must be called inside the business transaction ({@code MANDATORY}).
     *
     * <p>Use this overload — never the emission-millis four-argument form — for a fact about a
     * version-guarded aggregate (#1486): {@code aggregateVersion} must come from that aggregate's
     * flushed JPA {@code @Version}, which strictly increments on every committed mutation, so it
     * can never tie the way emission-timestamp millis could. The caller is responsible for
     * flushing the pending mutation before reading the version.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(
            @NonNull String eventType,
            int schemaVersion,
            @NonNull UUID aggregateId,
            long aggregateVersion,
            @NonNull Object payload) {
        DomainEventEnvelope<Object> envelope = DomainEventEnvelope.of(
                eventType, schemaVersion, aggregateId, aggregateVersion, SOURCE_SERVICE, null, null, payload, clock);
        OutboxEvent event = OutboxEvent.builder()
                .topic(eventsTopic)
                .recordKey(envelope.recordKey())
                .payload(serialize(envelope))
                .createdAt(Instant.now(clock))
                .build();
        outboxEventRepository.save(event);
        log.debug("Queued outbox event type={} topic={} key={}", eventType, eventsTopic, envelope.recordKey());
    }

    private String serialize(DomainEventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize event envelope for type: " + envelope.eventType(), e);
        }
    }
}
