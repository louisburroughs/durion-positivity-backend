package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
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
 * Drains {@code supplier_event_outbox} to Kafka (ADR-0044 §4).
 *
 * <p>At-least-once: a row is marked published only after the broker acknowledges, so a crash
 * between send and mark re-sends and consumers dedupe by {@code eventId}. Rows go out in id order
 * and the batch <strong>stops at the first failure</strong> — for PRICAT that ordering is not
 * cosmetic: a completion event that overtook a stuck chunk would tell a consumer the import is
 * complete while lines are still missing.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.supplier.kafka", name = "enabled", havingValue = "true")
public class SupplierOutboxPublisher {

    private final SupplierOutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    @Value("${pos.supplier.outbox.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    public SupplierOutboxPublisher(
            SupplierOutboxEventRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.publishedCounter = registry == null
                ? null
                : Counter.builder("supplier.outbox.published")
                        .description("Supplier outbox events successfully published to Kafka")
                        .register(registry);
        this.failedCounter = registry == null
                ? null
                : Counter.builder("supplier.outbox.publish.failures")
                        .description("Supplier outbox publish attempts that failed")
                        .register(registry);
    }

    /** Publishes pending rows in id order, stopping at the first failure to preserve ordering. */
    @Scheduled(fixedDelayString = "${pos.supplier.outbox.poll-interval-ms:1000}")
    public void publishPending() {
        List<SupplierOutboxEventEntity> pending = outboxRepository.findTop100ByPublishedAtIsNullOrderByIdAsc();
        for (SupplierOutboxEventEntity event : pending) {
            if (!publish(event)) {
                break;
            }
        }
    }

    private boolean publish(SupplierOutboxEventEntity event) {
        try {
            kafkaTemplate
                    .send(event.getTopic(), event.getRecordKey(), event.getPayload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            event.setPublishedAt(Instant.now(clock));
            event.setAttempts(0);
            event.setLastError(null);
            outboxRepository.save(event);
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

    private void recordFailure(SupplierOutboxEventEntity event, Exception e) {
        event.setAttempts(event.getAttempts() + 1);
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        event.setLastError(message.length() > 2000 ? message.substring(0, 2000) : message);
        outboxRepository.save(event);
        increment(failedCounter);
        log.warn(
                "Supplier outbox publish failed id={} topic={} type={} attempts={}",
                event.getId(),
                event.getTopic(),
                event.getEventType(),
                event.getAttempts(),
                e);
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
