package com.positivity.platformsender.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.platformsender.internal.entity.OutboxEvent;
import com.positivity.platformsender.internal.repository.OutboxEventRepository;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional-outbox writer for {@code sender.outcomes.v1} (ADR-0044 §4).
 *
 * <p>Serializes a full {@link DomainEventEnvelope} into {@code event_outbox} within the caller's
 * transaction, so an outcome is queued if and only if the provider event that produced it is
 * recorded as relayed. {@link OutboxPublisher} drains the table to Kafka with at-least-once
 * delivery. A {@code @KafkaRails} bean: absent in the broker-less dev/test profiles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaRails
public class OutboxEventWriter {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;
    private final TenantResolver tenantResolver;

    /**
     * Queue an envelope under an explicit record key, as part of the current transaction
     * ({@code MANDATORY}). The key is the caller's choice because FI-2 §2 keys the outcome topic by
     * {@code providerMessageId}, not by the envelope's aggregate.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(@NonNull String topic, @NonNull String recordKey, @NonNull DomainEventEnvelope<?> envelope) {
        // The envelope carries the tenant the row and the Kafka header carry (ADR-0062 §3); an
        // envelope built for another tenant is refused rather than re-labelled.
        DomainEventEnvelope<?> stamped = envelope.stampedWith(tenantResolver.require());
        OutboxEvent event = OutboxEvent.builder()
                .tenantId(stamped.requireTenantId())
                .topic(topic)
                .recordKey(recordKey)
                .payload(serialize(stamped))
                .createdAt(Instant.now(clock))
                .build();
        outboxEventRepository.save(event);
        log.debug("Queued outbox event type={} topic={} key={}", envelope.eventType(), topic, recordKey);
    }

    private String serialize(DomainEventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize event envelope for type: " + envelope.eventType(), e);
        }
    }
}
