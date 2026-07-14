package com.positivity.invoice.internal.config;

import com.positivity.invoice.internal.entity.OutboxEvent;
import com.positivity.invoice.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code event_outbox} to Kafka (ADR-0044 §4).
 *
 * <p>At-least-once: a row is marked published (in its own short transaction, so no DB connection
 * is held across the blocking broker send) only after the broker acknowledges, and a crash between
 * send and mark re-sends on the next poll — consumers must be idempotent by {@code eventId}. Rows
 * are processed in id order (UUIDv7 = insertion time); the batch stops at the first failure so a
 * struggling broker cannot reorder events, and failed rows retry on every poll with
 * {@code attempts}/{@code last_error} recorded for alerting.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    @Value("${pos.invoice.outbox.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.publishedCounter = registry == null
                ? null
                : Counter.builder("invoice.outbox.published")
                        .description("Outbox events successfully published to Kafka")
                        .register(registry);
        this.failedCounter = registry == null
                ? null
                : Counter.builder("invoice.outbox.publish.failures")
                        .description("Outbox publish attempts that failed")
                        .register(registry);
    }

    @Scheduled(fixedDelayString = "${pos.invoice.outbox.poll-interval-ms:1000}")
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
            event.setAttempts(0);
            event.setLastError(null);
            outboxEventRepository.save(event);
            increment(publishedCounter);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(event, e);
            return false;
        } catch (Exception e) {
            recordFailure(event, e);
            return false;
        }
    }

    private void recordFailure(OutboxEvent event, Exception e) {
        event.setAttempts(event.getAttempts() + 1);
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        event.setLastError(message.length() > 2000 ? message.substring(0, 2000) : message);
        outboxEventRepository.save(event);
        increment(failedCounter);
        log.warn(
                "Outbox publish failed id={} topic={} key={} attempts={}",
                event.getId(),
                event.getTopic(),
                event.getRecordKey(),
                event.getAttempts(),
                e);
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
