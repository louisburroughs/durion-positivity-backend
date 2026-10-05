package com.positivity.platformsender.internal.service;

import com.positivity.kafka.common.KafkaRails;
import com.positivity.platformsender.internal.config.SenderProperties;
import com.positivity.tenancy.PlatformScoped;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Long-polls the outcomes queue: the SQS queue the SES and End User Messaging configuration sets
 * publish their events into (through SNS). Each message is mapped ({@link ProviderOutcomeMapper})
 * and relayed ({@link OutcomeRelay}), then deleted.
 *
 * <p>Deletion is the acknowledgement, so delivery is at-least-once and the relay deduplicates.
 * A message is deleted once relayed or once it is known to mean nothing (an interim status). One
 * that cannot be relayed (unparsable, or not tagged by this sender) or whose relay failed is left
 * on the queue: it reappears after the visibility timeout and, past the queue's redrive
 * {@code maxReceiveCount}, moves to its dead-letter queue for someone to look at.
 */
@Slf4j
@Component
@KafkaRails
@ConditionalOnProperty(prefix = "pos.platform-sender.outcomes", name = "enabled", havingValue = "true")
public class OutcomeQueuePoller {

    private static final int MAX_WAIT_SECONDS = 20;
    private static final int MAX_BATCH = 10;

    private final SqsClient sqs;
    private final ProviderOutcomeMapper mapper;
    private final OutcomeRelay relay;
    private final SenderProperties.Outcomes settings;
    private final @Nullable MeterRegistry meterRegistry;

    public OutcomeQueuePoller(
            SqsClient sqs,
            ProviderOutcomeMapper mapper,
            OutcomeRelay relay,
            SenderProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.sqs = sqs;
        this.mapper = mapper;
        this.relay = relay;
        this.settings = properties.outcomes();
        this.meterRegistry = meterRegistry.getIfAvailable();
        if (settings.queueUrl() == null || settings.queueUrl().isBlank()) {
            throw new IllegalStateException(
                    "pos.platform-sender.outcomes.enabled is true but pos.platform-sender.outcomes.queue-url is not set");
        }
    }

    @PlatformScoped(
            reason = "drains the provider-outcome queue, which holds every tenant's events; each event names its"
                    + " tenant in the tags the sender set, and OutcomeRelay relays it bound to that tenant")
    @Scheduled(fixedDelayString = "${pos.platform-sender.outcomes.poll-delay-ms:1000}")
    public void poll() {
        List<Message> messages;
        try {
            messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                            .queueUrl(settings.queueUrl())
                            .maxNumberOfMessages(Math.clamp(settings.maxMessages(), 1, MAX_BATCH))
                            .waitTimeSeconds(Math.clamp(settings.waitTimeSeconds(), 0, MAX_WAIT_SECONDS))
                            .build())
                    .messages();
        } catch (SdkException e) {
            log.warn("Outcome queue receive failed; retrying on the next poll: {}", e.getMessage());
            count("receive_failed");
            return;
        }
        for (Message message : messages) {
            handle(message);
        }
    }

    void handle(@NonNull Message message) {
        ProviderOutcomeMapper.Mapping mapping = mapper.map(message.body());
        switch (mapping) {
            case ProviderOutcomeMapper.Ignored ignored -> {
                log.debug("Ignoring outcome-queue message {}: {}", message.messageId(), ignored.reason());
                count("ignored");
                delete(message);
            }
            case ProviderOutcomeMapper.Unusable unusable -> {
                log.warn(
                        "Leaving unusable outcome-queue message {} for the dead-letter queue: {}",
                        message.messageId(),
                        unusable.reason());
                count("unusable");
            }
            case ProviderOutcomeMapper.Mapped mapped -> {
                String dedupeKey = mapped.dedupeKey() != null ? mapped.dedupeKey() : message.messageId();
                try {
                    relay.relay(dedupeKey, mapped);
                } catch (RuntimeException e) {
                    // Left on the queue: the visibility timeout redelivers it, the ledger dedupes it.
                    log.warn(
                            "Relaying outcome-queue message {} failed; it will be redelivered", message.messageId(), e);
                    count("relay_failed");
                    return;
                }
                count("relayed");
                delete(message);
            }
        }
    }

    private void delete(Message message) {
        try {
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(settings.queueUrl())
                    .receiptHandle(message.receiptHandle())
                    .build());
        } catch (SdkException e) {
            // Harmless: the message comes back after the visibility timeout and is deduplicated.
            log.warn("Deleting outcome-queue message {} failed: {}", message.messageId(), e.getMessage());
        }
    }

    private void count(String result) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("platform-sender.outcomes.messages")
                .description("Provider outcome-queue messages by result")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
