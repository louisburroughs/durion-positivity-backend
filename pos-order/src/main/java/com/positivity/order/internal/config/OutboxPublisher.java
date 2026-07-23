package com.positivity.order.internal.config;

import com.positivity.order.internal.entity.OutboxEvent;
import com.positivity.order.internal.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code event_outbox} to Kafka (ADR-0044 §4, plan story D1). Mirrors the pos-invoice
 * publisher: at-least-once, id-ordered (UUIDv7 = insertion order), stop-at-first-failure so a
 * struggling broker cannot reorder events; failed rows retry every poll with
 * {@code attempts}/{@code last_error} recorded for alerting. Consumers dedup by {@code eventId}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    @Value("${pos.order.outbox.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    @Scheduled(fixedDelayString = "${pos.order.outbox.poll-interval-ms:1000}")
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByIdAsc();
        for (OutboxEvent event : pending) {
            if (!publish(event)) {
                // Stop at the first failure to preserve publish order; retry next poll.
                break;
            }
        }
    }

    private boolean publish(OutboxEvent event) {
        try {
            kafkaTemplate
                    .send(event.getTopic(), event.getRecordKey(), event.getPayload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            event.setPublishedAt(Instant.now(clock));
            event.setLastError(null);
            outboxEventRepository.save(event);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recordFailure(event, interrupted.getMessage());
            return false;
        } catch (Exception e) {
            recordFailure(event, e.getMessage());
            return false;
        }
    }

    private void recordFailure(OutboxEvent event, String message) {
        event.setAttempts(event.getAttempts() + 1);
        event.setLastError(message);
        outboxEventRepository.save(event);
        log.warn(
                "Outbox publish failed for event {} on topic {} (attempt {}): {}",
                event.getId(),
                event.getTopic(),
                event.getAttempts(),
                message);
    }
}
