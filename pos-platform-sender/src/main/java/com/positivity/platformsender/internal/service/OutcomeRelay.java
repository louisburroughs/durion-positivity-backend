package com.positivity.platformsender.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.sender.SenderMessageOutcomeV1;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.platformsender.internal.config.OutboxEventWriter;
import com.positivity.platformsender.internal.entity.ProcessedEvent;
import com.positivity.platformsender.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Queues one mapped provider outcome on {@code sender.outcomes.v1} (FI-2 §2) through the outbox,
 * under the tenant the message was sent for.
 *
 * <p>The outbox row and a {@code processed_events} mark keyed by the provider event's own id commit
 * together, so the queue's at-least-once redelivery (a crash before the queue message is deleted)
 * never publishes the same outcome twice under two envelope ids. The envelope's aggregate is the
 * caller's {@code messageId}; its version is the outcome's time in epoch milliseconds, a
 * last-writer-wins hint like the one pos-people-contact uses.
 */
@Slf4j
@Service
@KafkaRails
@ConditionalOnProperty(prefix = "pos.platform-sender.outcomes", name = "enabled", havingValue = "true")
public class OutcomeRelay {

    static final String OWNER = "provider-outcome";
    static final String SOURCE_SERVICE = "pos-platform-sender";

    private final OutboxEventWriter outboxEventWriter;
    private final ProcessedEventRepository processedEventRepository;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String outcomesTopic;

    public OutcomeRelay(
            OutboxEventWriter outboxEventWriter,
            ProcessedEventRepository processedEventRepository,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${pos.platform-sender.kafka.outcomes-topic:sender.outcomes.v1}") String outcomesTopic) {
        this.outboxEventWriter = outboxEventWriter;
        this.processedEventRepository = processedEventRepository;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.outcomesTopic = outcomesTopic;
    }

    /**
     * @param dedupeKey the provider event's id, stable across redeliveries
     * @return {@code true} when the outcome was queued, {@code false} when it already had been
     */
    public boolean relay(@NonNull String dedupeKey, ProviderOutcomeMapper.@NonNull Mapped mapped) {
        Boolean queued = TenantContext.callAs(
                mapped.tenantId(),
                () -> transaction.execute(_ -> {
                    if (processedEventRepository.existsById(dedupeKey)) {
                        return false;
                    }
                    SenderMessageOutcomeV1 outcome = mapped.outcome();
                    DomainEventEnvelope<SenderMessageOutcomeV1> envelope = DomainEventEnvelope.of(
                            mapped.eventType(),
                            SenderMessageOutcomeV1.SCHEMA_VERSION,
                            outcome.messageId(),
                            outcome.occurredAt().toEpochMilli(),
                            SOURCE_SERVICE,
                            null,
                            SOURCE_SERVICE,
                            outcome,
                            clock);
                    outboxEventWriter.publish(outcomesTopic, outcome.providerMessageId(), envelope);
                    processedEventRepository.save(ProcessedEvent.builder()
                            .eventId(dedupeKey)
                            .owner(OWNER)
                            .processedAt(Instant.now(clock))
                            .build());
                    return true;
                }));
        boolean wasQueued = Boolean.TRUE.equals(queued);
        if (wasQueued) {
            log.debug(
                    "Queued {} for message {} ({})",
                    mapped.eventType(),
                    mapped.outcome().messageId(),
                    mapped.outcome().providerMessageId());
        }
        return wasQueued;
    }
}
