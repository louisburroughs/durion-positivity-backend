package com.positivity.workorder.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes reconciliation manifests for the workorder fact topic (ADR-0044 §4, issue #840).
 *
 * <p>Each closed window gets one {@link ReconciliationManifestV1} on
 * {@code workorder.manifest.v1} summarizing the events published from {@code event_outbox} whose
 * eventId (UUIDv7) timestamp falls in the window. Consumers recompute the summary from their
 * processing log and request an outbox replay over {@code workorder.commands.v1} on drift.
 *
 * <p>Manifests are sent directly (no outbox): a lost manifest is self-healing — the next run
 * re-publishes it, and consumers can additionally alert on manifest absence. Publication waits
 * {@code grace} after window close so in-flight publishes and consumer lag settle before the
 * comparison, avoiding false drift. Re-publishing the same window (e.g. after a restart) is
 * harmless because the consumer-side comparison is stateless and idempotent.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class ManifestPublisher {

    /** Covers the sub-millisecond skew between outbox {@code createdAt} and the eventId timestamp. */
    private static final Duration CREATED_AT_SLACK = Duration.ofSeconds(1);

    private static final String DOMAIN = "workorder";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    @Value("${workorder.kafka.events-topic:workorder.events.v1}")
    private String eventsTopic;

    @Value("${workorder.manifest.topic:workorder.manifest.v1}")
    private String manifestTopic;

    /** Reconciliation window length. */
    @Value("${workorder.manifest.window:PT1H}")
    private Duration window;

    /** How long after a window closes before its manifest is published. */
    @Value("${workorder.manifest.grace:PT5M}")
    private Duration grace;

    @Value("${workorder.manifest.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    @Nullable
    private Instant lastPublishedWindowEnd;

    public ManifestPublisher(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.publishedCounter = registry == null
                ? null
                : Counter.builder("workorder.manifest.published")
                        .description("Reconciliation manifests published")
                        .register(registry);
        this.failedCounter = registry == null
                ? null
                : Counter.builder("workorder.manifest.publish.failures")
                        .description("Reconciliation manifest publish attempts that failed")
                        .register(registry);
    }

    @Scheduled(fixedDelayString = "${workorder.manifest.poll-interval-ms:300000}")
    public void publishDueManifest() {
        Instant windowEnd = latestClosedWindowEnd();
        if (windowEnd == null || (lastPublishedWindowEnd != null && !windowEnd.isAfter(lastPublishedWindowEnd))) {
            return;
        }
        Instant windowStart = windowEnd.minus(window);
        try {
            publishManifest(windowStart, windowEnd);
            lastPublishedWindowEnd = windowEnd;
            increment(publishedCounter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(windowStart, windowEnd, e);
        } catch (Exception e) {
            recordFailure(windowStart, windowEnd, e);
        }
    }

    /** End of the most recent window that closed at least {@code grace} ago, aligned to the window length. */
    private @Nullable Instant latestClosedWindowEnd() {
        long windowMillis = window.toMillis();
        long cutoff = Instant.now(clock).minus(grace).toEpochMilli();
        long aligned = Math.floorDiv(cutoff, windowMillis) * windowMillis;
        return aligned <= 0 ? null : Instant.ofEpochMilli(aligned);
    }

    private void publishManifest(Instant windowStart, Instant windowEnd) throws Exception {
        List<OutboxEvent> candidates = outboxEventRepository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(
                eventsTopic, windowStart.minus(CREATED_AT_SLACK), windowEnd.plus(CREATED_AT_SLACK));

        List<String> eventIds = new ArrayList<>();
        TreeMap<String, Long> eventTypeCounts = new TreeMap<>();
        for (OutboxEvent row : candidates) {
            JsonNode envelope = objectMapper.readTree(row.getPayload());
            String eventId = envelope.path("eventId").stringValue(null);
            if (eventId == null || eventId.isBlank()) {
                log.warn("Outbox row {} has no eventId in its payload; excluded from manifest", row.getId());
                continue;
            }
            if (!UuidV7Timestamps.isInWindow(UUID.fromString(eventId), windowStart, windowEnd)) {
                continue;
            }
            eventIds.add(eventId);
            String eventType = envelope.path("eventType").stringValue(null);
            eventTypeCounts.merge(eventType == null || eventType.isBlank() ? "unknown" : eventType, 1L, Long::sum);
        }

        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                windowStart,
                windowEnd,
                eventIds.size(),
                ReconciliationManifestV1.checksumOf(eventIds),
                eventTypeCounts.isEmpty() ? null : eventTypeCounts);

        DomainEventEnvelope<ReconciliationManifestV1> envelope = DomainEventEnvelope.of(
                ReconciliationManifestV1.eventTypeFor(DOMAIN),
                1,
                manifestAggregateId(windowStart),
                windowStart.getEpochSecond(),
                "pos-workorder",
                null,
                null,
                manifest,
                clock);

        kafkaTemplate
                .send(manifestTopic, envelope.recordKey(), objectMapper.writeValueAsString(envelope))
                .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        log.info(
                "Published reconciliation manifest window=[{}, {}) events={} topic={}",
                windowStart,
                windowEnd,
                eventIds.size(),
                manifestTopic);
    }

    /** Deterministic per-window aggregate id, so re-published manifests key to the same partition. */
    private UUID manifestAggregateId(Instant windowStart) {
        return UUID.nameUUIDFromBytes(
                (DOMAIN + ".manifest:" + eventsTopic + ":" + windowStart).getBytes(StandardCharsets.UTF_8));
    }

    private void recordFailure(Instant windowStart, Instant windowEnd, Exception e) {
        increment(failedCounter);
        log.warn("Reconciliation manifest publish failed window=[{}, {})", windowStart, windowEnd, e);
    }

    private void increment(@Nullable Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
