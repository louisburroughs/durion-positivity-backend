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
 * <p>The JSON envelope shape (eventId, eventType, occurredAtUtc, sourceService, payload) is
 * unchanged from the previous producer, so existing consumers (pos-customer's workorder event
 * handler) keep working.
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
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(@NonNull String eventType, @NonNull String key, @NonNull Object payload) {
        OutboxEvent event = OutboxEvent.builder()
                .topic(eventsTopic)
                .recordKey(key)
                .payload(serializeEnvelope(eventType, payload))
                .createdAt(Instant.now(clock))
                .build();
        outboxEventRepository.save(event);
        log.debug("Queued outbox event type={} topic={} key={}", eventType, eventsTopic, key);
    }

    private String serializeEnvelope(String eventType, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUIDv7Generator.generate().toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAtUtc", Instant.now(clock));
        envelope.put("sourceService", SOURCE_SERVICE);
        envelope.put("payload", payload);

        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize Kafka event envelope for type: " + eventType, e);
        }
    }
}
