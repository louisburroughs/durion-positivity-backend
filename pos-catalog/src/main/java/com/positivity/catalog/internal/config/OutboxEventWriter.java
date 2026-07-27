package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.entity.OutboxEvent;
import com.positivity.catalog.internal.repository.OutboxEventRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional-outbox writer for catalog domain events (ADR-0044 §4, #924).
 *
 * <p>Serializes a full {@link DomainEventEnvelope} into {@code event_outbox} within the caller's
 * transaction, so an event exists if and only if the business state change committed.
 * {@link OutboxPublisher} drains the table to Kafka with at-least-once delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.catalog.kafka", name = "enabled", havingValue = "true")
public class OutboxEventWriter {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;

    /** Queue an envelope for publication as part of the current transaction ({@code MANDATORY}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(@NonNull String topic, @NonNull DomainEventEnvelope<?> envelope) {
        OutboxEvent event = OutboxEvent.builder()
                .topic(topic)
                .recordKey(envelope.recordKey())
                .payload(serialize(envelope))
                .createdAt(Instant.now(clock))
                .build();
        outboxEventRepository.save(event);
        log.debug("Queued outbox event type={} topic={} key={}", envelope.eventType(), topic, envelope.recordKey());
    }

    private String serialize(DomainEventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize event envelope for type: " + envelope.eventType(), e);
        }
    }
}
