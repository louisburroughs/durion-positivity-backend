package com.positivity.supplier.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes a domain event into {@code supplier_event_outbox} inside the caller's transaction
 * (ADR-0044 §4).
 *
 * <p>{@code MANDATORY} propagation is the point: an event may only be queued as part of the
 * transaction that committed the state it describes. Without it, a PRICAT chunk could be published
 * for staged lines that were subsequently rolled back, and a consumer would apply prices that never
 * existed on this side.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupplierOutboxEventWriter {

    private final ObjectMapper objectMapper;
    private final SupplierOutboxEventRepository outboxRepository;

    /**
     * Queues one envelope for publication.
     *
     * @param topic    destination topic, e.g. {@code supplier.events.v1}
     * @param envelope the event; its {@code aggregateId} becomes the Kafka record key, so all
     *                 events of one import stay in order on one partition
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(@NonNull String topic, @NonNull DomainEventEnvelope<?> envelope) {
        SupplierOutboxEventEntity row = SupplierOutboxEventEntity.builder()
                .topic(topic)
                .recordKey(envelope.aggregateId().toString())
                .eventType(envelope.eventType())
                .payload(serialize(envelope))
                .build();
        outboxRepository.save(row);
        log.debug(
                "Queued supplier outbox event type={} topic={} key={} sequence={}",
                envelope.eventType(),
                topic,
                envelope.aggregateId(),
                envelope.aggregateVersion());
    }

    private String serialize(DomainEventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize event envelope for type: " + envelope.eventType(), e);
        }
    }
}
