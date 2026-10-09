package com.positivity.tax.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.tax.internal.entity.OutboxEvent;
import com.positivity.tax.internal.repository.OutboxEventRepository;
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
 * Transactional-outbox writer for pos-tax's facts (ADR-0044 §4; CAP:550 S32c).
 *
 * <p>Serializes a full {@link DomainEventEnvelope} into {@code event_outbox} within the caller's transaction, so a
 * {@code tax.registration.changed} row exists if and only if the registration change committed. {@link
 * OutboxPublisher} drains the table to Kafka with at-least-once delivery.
 *
 * <p>Unlike the publisher, this writer is not {@code @KafkaRails}: writing the row needs only the database, and a
 * registration change must never commit without its fact, in any profile. Rows written where no broker runs (the
 * broker-less {@code dev} profile) wait unpublished until a publisher drains them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventWriter {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;
    private final TenantResolver tenantResolver;

    /**
     * Queue an envelope for publication as part of the current transaction. Must be called inside the business
     * transaction ({@code MANDATORY}), which is the whole point of the outbox.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(@NonNull String topic, @NonNull DomainEventEnvelope<?> envelope) {
        // The envelope carries the tenant the row and the Kafka header carry (ADR-0062 §3); an envelope built for
        // another tenant is refused rather than re-labelled.
        DomainEventEnvelope<?> stamped = envelope.stampedWith(tenantResolver.require());
        OutboxEvent event = OutboxEvent.builder()
                .tenantId(stamped.requireTenantId())
                .topic(topic)
                .recordKey(envelope.recordKey())
                .payload(serialize(stamped))
                .createdAt(Instant.now(clock))
                .build();
        outboxEventRepository.save(event);
        log.debug("Queued outbox event type={} topic={} key={}", envelope.eventType(), topic, envelope.recordKey());
    }

    private String serialize(DomainEventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            // The message names the type only: the payload carries a registration number.
            throw new IllegalStateException("Unable to serialize event envelope for type: " + envelope.eventType());
        }
    }
}
