package com.positivity.workorder.internal.config;

import com.positivity.workorder.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Outbox health gauges (issue #838, ADR-0044 §4 operational signals):
 *
 * <ul>
 *   <li>{@code workorder.outbox.pending} — unpublished row count; sustained growth means the drain
 *       is failing or stopped.
 *   <li>{@code workorder.outbox.oldest.age.seconds} — age of the drain head; alert at 5 min (warn)
 *       and 15 min (critical). Because the publisher stops its batch at the first failure, one
 *       stuck row shows up here immediately.
 * </ul>
 *
 * <p>Each gauge read issues one cheap query (count / first row on the partial unpublished index)
 * per Prometheus scrape (15 s).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class OutboxMetrics {

    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    public OutboxMetrics(
            OutboxEventRepository outboxEventRepository, Clock clock, ObjectProvider<MeterRegistry> meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            log.warn("No MeterRegistry available; outbox gauges not registered");
            return;
        }
        Gauge.builder("workorder.outbox.pending", this, OutboxMetrics::pendingCount)
                .description("Unpublished event_outbox rows awaiting Kafka publish")
                .register(registry);
        Gauge.builder("workorder.outbox.oldest.age.seconds", this, OutboxMetrics::oldestPendingAgeSeconds)
                .description("Age in seconds of the oldest unpublished event_outbox row (0 when drained)")
                .register(registry);
    }

    double pendingCount() {
        return outboxEventRepository.countByPublishedAtIsNull();
    }

    double oldestPendingAgeSeconds() {
        return outboxEventRepository
                .findFirstByPublishedAtIsNullOrderByIdAsc()
                .map(event -> Math.max(
                        0,
                        Duration.between(event.getCreatedAt(), Instant.now(clock))
                                .toSeconds()))
                .orElse(0L)
                .doubleValue();
    }
}
