package com.positivity.peoplecontact.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.peoplecontact.internal.entity.OutboxEvent;
import com.positivity.peoplecontact.internal.repository.OutboxEventRepository;
import com.positivity.tenancy.PlatformScoped;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * Publishes reconciliation manifests for the people-contact fact topic (ADR-0044 §4, issue #874).
 *
 * <p>Each closed window gets one {@link ReconciliationManifestV1} per tenant on
 * {@code people-contact.manifest.v1} summarizing the events published from {@code event_outbox} whose
 * eventId (UUIDv7) timestamp falls in the window. Consumers recompute the summary from their
 * processed-events log and request an outbox replay over {@code people-contact.commands.v1} on drift. Every active tenant of the
 * {@link TenantRegistry} gets a manifest each window, zero-count when it published nothing (ADR-0062 §3).
 *
 * <p>Manifests are sent directly (no outbox): a lost manifest is self-healing — the next run
 * re-publishes it, and consumers can additionally alert on manifest absence. Publication waits
 * {@code grace} after window close so in-flight publishes and consumer lag settle before the
 * comparison, avoiding false drift. Re-publishing the same window (e.g. after a restart) is
 * harmless because the consumer-side comparison is stateless and idempotent.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.people-contact.kafka", name = "enabled", havingValue = "true")
public class ManifestPublisher {

    /** Covers the sub-millisecond skew between outbox {@code createdAt} and the eventId timestamp. */
    private static final Duration CREATED_AT_SLACK = Duration.ofSeconds(1);

    private static final String DOMAIN = "people-contact";

    /** Catch-up bound per run — 24 windows covers a day-long outage at the default 1h window. */
    private static final int MAX_WINDOWS_PER_RUN = 24;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TenantRegistry tenantRegistry;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    @Value("${pos.people-contact.kafka.events-topic:people-contact.events.v1}")
    private String eventsTopic;

    @Value("${pos.people-contact.manifest.topic:people-contact.manifest.v1}")
    private String manifestTopic;

    /** Reconciliation window length. */
    @Value("${pos.people-contact.manifest.window:PT1H}")
    private Duration window;

    /** How long after a window closes before its manifest is published. */
    @Value("${pos.people-contact.manifest.grace:PT5M}")
    private Duration grace;

    @Value("${pos.people-contact.manifest.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    @Nullable
    private Instant lastPublishedWindowEnd;

    public ManifestPublisher(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            TenantRegistry tenantRegistry,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tenantRegistry = tenantRegistry;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.publishedCounter = registry == null
                ? null
                : Counter.builder("people_contact.manifest.published")
                        .description("Reconciliation manifests published")
                        .register(registry);
        this.failedCounter = registry == null
                ? null
                : Counter.builder("people_contact.manifest.publish.failures")
                        .description("Reconciliation manifest publish attempts that failed")
                        .register(registry);
    }

    @PlatformScoped(
            reason = "reads event_outbox, a global table, for every tenant's rows of the window, then publishes one"
                    + " manifest per tenant, each stamped with its tenant; the ledger it summarises carries the"
                    + " tenant as data, so no tenant-scoped table is touched")
    @Scheduled(fixedDelayString = "${pos.people-contact.manifest.poll-interval-ms:300000}")
    public void publishDueManifest() {
        Instant latestClosed = latestClosedWindowEnd();
        if (latestClosed == null || (lastPublishedWindowEnd != null && !latestClosed.isAfter(lastPublishedWindowEnd))) {
            return;
        }
        // First run publishes only the latest closed window (no historic backfill on boot);
        // afterwards every window is published in order so scheduler gaps cannot skip one
        // permanently.
        Instant windowEnd = lastPublishedWindowEnd == null ? latestClosed : lastPublishedWindowEnd.plus(window);
        int published = 0;
        while (!windowEnd.isAfter(latestClosed) && published < MAX_WINDOWS_PER_RUN) {
            Instant windowStart = windowEnd.minus(window);
            try {
                publishManifests(windowStart, windowEnd);
                lastPublishedWindowEnd = windowEnd;
                published++;
                windowEnd = windowEnd.plus(window);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(windowStart, windowEnd, e);
                return;
            } catch (Exception e) {
                // Retry this window on the next poll; later windows stay queued behind it.
                recordFailure(windowStart, windowEnd, e);
                return;
            }
        }
        if (!windowEnd.isAfter(latestClosed)) {
            log.info("Manifest catch-up capped at {} windows this run; continuing next poll", MAX_WINDOWS_PER_RUN);
        }
    }

    /** End of the most recent window that closed at least {@code grace} ago, aligned to the window length. */
    private @Nullable Instant latestClosedWindowEnd() {
        long windowMillis = window.toMillis();
        long cutoff = Instant.now(clock).minus(grace).toEpochMilli();
        long aligned = Math.floorDiv(cutoff, windowMillis) * windowMillis;
        return aligned <= 0 ? null : Instant.ofEpochMilli(aligned);
    }

    /**
     * One manifest per tenant for the window (ADR-0062 §3): the window's published rows are grouped
     * by the tenant each outbox row carries, and every active tenant of the registry gets a manifest
     * too, zero-count when it published nothing, so a consumer can alert on manifest absence per
     * tenant rather than reading silence as health.
     */
    private void publishManifests(Instant windowStart, Instant windowEnd) throws Exception {
        List<OutboxEvent> candidates = outboxEventRepository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(
                eventsTopic, windowStart.minus(CREATED_AT_SLACK), windowEnd.plus(CREATED_AT_SLACK));

        Map<UUID, WindowSummary> perTenant = new LinkedHashMap<>();
        for (UUID tenantId : new TreeSet<>(tenantRegistry.activeTenantIds())) {
            perTenant.put(tenantId, new WindowSummary());
        }
        for (OutboxEvent row : candidates) {
            // One malformed row must not block the window's manifest forever: skip it with a
            // warning; the consumer-side mismatch it may cause is visible drift.
            try {
                JsonNode envelope = objectMapper.readTree(row.getPayload());
                String eventId = envelope.path("eventId").stringValue(null);
                if (eventId == null || eventId.isBlank()) {
                    log.warn("Outbox row {} has no eventId in its payload; excluded from manifest", row.getId());
                    continue;
                }
                if (!UuidV7Timestamps.isInWindow(UUID.fromString(eventId), windowStart, windowEnd)) {
                    continue;
                }
                UUID tenantId = row.getTenantId();
                if (tenantId == null) {
                    log.warn("Outbox row {} carries no tenant; excluded from manifest", row.getId());
                    continue;
                }
                String eventType = envelope.path("eventType").stringValue(null);
                perTenant.computeIfAbsent(tenantId, _ -> new WindowSummary()).add(eventId, eventType);
            } catch (Exception e) {
                log.warn("Outbox row {} has an unparsable payload/eventId; excluded from manifest", row.getId(), e);
            }
        }
        if (perTenant.isEmpty()) {
            log.warn(
                    "No manifest published for window=[{}, {}): the window has no rows and the tenant registry is empty",
                    windowStart,
                    windowEnd);
            return;
        }

        for (Map.Entry<UUID, WindowSummary> entry : perTenant.entrySet()) {
            publishManifest(entry.getKey(), windowStart, windowEnd, entry.getValue());
        }
    }

    private void publishManifest(UUID tenantId, Instant windowStart, Instant windowEnd, WindowSummary summary)
            throws Exception {
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                tenantId,
                windowStart,
                windowEnd,
                summary.eventIds.size(),
                ReconciliationManifestV1.checksumOf(summary.eventIds),
                summary.eventTypeCounts.isEmpty() ? null : summary.eventTypeCounts);

        // A manifest is sent straight to Kafka, bypassing the outbox writer that would otherwise
        // stamp the tenant: envelope and header both carry the manifest's tenant, so the
        // consumer's record interceptor binds it and the listener compares that tenant's ledger.
        DomainEventEnvelope<ReconciliationManifestV1> envelope = DomainEventEnvelope.of(
                ReconciliationManifestV1.eventTypeFor(DOMAIN),
                ReconciliationManifestV1.SCHEMA_VERSION,
                manifestAggregateId(tenantId, windowStart),
                windowStart.getEpochSecond(),
                "pos-people-contact",
                tenantId,
                null,
                null,
                manifest,
                clock);

        kafkaTemplate
                .send(TenantKafkaHeaders.record(
                        manifestTopic, envelope.recordKey(), objectMapper.writeValueAsString(envelope), tenantId))
                .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        increment(publishedCounter);
        log.info(
                "Published reconciliation manifest tenant={} window=[{}, {}) events={} topic={}",
                tenantId,
                windowStart,
                windowEnd,
                summary.eventIds.size(),
                manifestTopic);
    }

    /**
     * Deterministic per-tenant, per-window aggregate id: re-published manifests key to the same
     * partition, and one window's manifests for different tenants land on distinct keys.
     */
    private UUID manifestAggregateId(UUID tenantId, Instant windowStart) {
        return UUID.nameUUIDFromBytes((DOMAIN + ".manifest:" + eventsTopic + ":" + windowStart + ":" + tenantId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The eventIds and per-type counts of one tenant's events in a window. */
    private static final class WindowSummary {
        private final List<String> eventIds = new ArrayList<>();
        private final TreeMap<String, Long> eventTypeCounts = new TreeMap<>();

        void add(String eventId, @Nullable String eventType) {
            eventIds.add(eventId);
            eventTypeCounts.merge(eventType == null || eventType.isBlank() ? "unknown" : eventType, 1L, Long::sum);
        }
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
