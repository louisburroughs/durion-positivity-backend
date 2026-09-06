package com.positivity.accounting.internal.config;

import com.positivity.accounting.internal.entity.KafkaOutboxEvent;
import com.positivity.accounting.internal.repository.KafkaOutboxEventRepository;
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
 * Transactional-outbox writer for the Kafka facts pos-accounting publishes (ADR-0044 §4, issue
 * #1843) — today the {@code accounting.invoice.gl-posted} fact on {@code accounting.events.v1}.
 *
 * <p>Serializes a full {@link DomainEventEnvelope} into {@code kafka_event_outbox} within the
 * caller's transaction, so a fact exists if and only if the journal entry it describes committed.
 * {@link OutboxPublisher} drains the table to Kafka with at-least-once delivery. Mirrors
 * pos-invoice's writer; the bean is conditional on the module's Kafka flag, and callers hold it
 * through an {@code ObjectProvider} so publishing degrades to a no-op when Kafka is off.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class OutboxEventWriter {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final KafkaOutboxEventRepository outboxEventRepository;

    /**
     * Queue an envelope for publication as part of the current transaction. Must be called inside
     * the business transaction ({@code MANDATORY}) — that is the whole point of the outbox.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(@NonNull String topic, @NonNull DomainEventEnvelope<?> envelope) {
        KafkaOutboxEvent event = KafkaOutboxEvent.builder()
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
