package com.positivity.workorder.internal.config;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>Replaces the previous direct {@code KafkaTemplate} producer: the serialized event is stored
 * in {@code event_outbox} within the caller's transaction, so an event exists if and only if the
 * business state change committed. {@link OutboxPublisher} drains the table to Kafka with
 * at-least-once delivery.
 *
 * <p>The JSON envelope shape is (eventId, eventType, schemaVersion, occurredAtUtc,
 * aggregateVersion, sourceService, payload). Every field the original producer emitted is still
 * emitted under the same name, so existing consumers (pos-customer's workorder event handler)
 * keep working; {@code schemaVersion} was added by #1286 and is additive.
 *
 * <p>This module hand-builds the envelope instead of using {@link
 * com.positivity.domainevents.DomainEventEnvelope} like the other thirteen modules, because that
 * type rejects underscores in the event type and several of this module's live types contain them
 * ({@code workorder.job_time.recorded.v1}, {@code workorder.work_session.started.v1}, …).
 * Reconciling the two means renaming those types, which is a breaking change for every consumer
 * that matches them as literals — see #1286.
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
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(@NonNull String eventType, int schemaVersion, @NonNull String key, @NonNull Object payload) {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1 but was: " + schemaVersion);
        }
        OutboxEvent event = OutboxEvent.builder()
                .topic(eventsTopic)
                .recordKey(key)
                .payload(serializeEnvelope(eventType, schemaVersion, payload))
                .createdAt(Instant.now(clock))
                .build();
        outboxEventRepository.save(event);
        log.debug("Queued outbox event type={} topic={} key={}", eventType, eventsTopic, key);
    }

    private String serializeEnvelope(String eventType, int schemaVersion, Object payload) {
        Instant occurredAt = Instant.now(clock);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUIDv7Generator.generate().toString());
        envelope.put("eventType", eventType);
        // Which payload shape this is. Additive: the existing consumers read this envelope as a
        // JsonNode tree and pull fields by name, so a key they do not know is ignored. Kept as a
        // plain map rather than DomainEventEnvelope because that type rejects the underscores in
        // several of this module's event types (see #1286).
        envelope.put("schemaVersion", schemaVersion);
        envelope.put("occurredAtUtc", occurredAt);
        // Emission-timestamp LWW hint for replica stale guards (ADR-0044 §6, #897) — additive,
        // legacy consumers of this envelope ignore it.
        envelope.put("aggregateVersion", occurredAt.toEpochMilli());
        envelope.put("sourceService", SOURCE_SERVICE);
        envelope.put("payload", payload);

        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize Kafka event envelope for type: " + eventType, e);
        }
    }
}
