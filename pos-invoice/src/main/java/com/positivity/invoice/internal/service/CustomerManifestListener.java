package com.positivity.invoice.internal.service;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer-side reconciliation for the customer-party replica (ADR-0044 §4, #891).
 *
 * <p>For every {@link ReconciliationManifestV1} on {@code customer.manifest.v1}, recomputes the
 * same count + checksum from {@code processed_events} (owner {@code customer}) — window
 * membership is the UUIDv7 timestamp embedded in each recorded eventId, exactly the definition
 * the owner used. On mismatch it increments {@code replica.drift} and publishes a
 * {@code customer.outbox.replay-requested} command for the window; the replayed events are
 * deduplicated by the {@code processed_events} primary key, so repair is idempotent. Manifests are per
 * tenant (ADR-0062 §3): the listener runs under the manifest\'s tenant, compares it against that
 * tenant\'s ledger rows only, tags the drift metric with the tenant and sends the replay command
 * with the tenant header so the owner replays only that tenant\'s events.
 *
 * <p>Note: this module intentionally processes only the party facts on {@code customer.events.v1}
 * yet the owner's manifest counts every fact in the window, so unprocessed event types would show
 * as permanent drift — therefore the events listener records every eventId it sees (including
 * ignored types) in {@code processed_events}. That is exactly what its idempotency guard already
 * does by saving after the type switch.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class CustomerManifestListener {

    private static final String REPLAY_COMMAND_TYPE = "customer.outbox.replay-requested";

    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final @Nullable MeterRegistry meterRegistry;

    @Value("${pos.invoice.kafka.customer-commands-topic:customer.commands.v1}")
    private String customerCommandsTopic;

    public CustomerManifestListener(
            ProcessedEventRepository processedEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.processedEventRepository = processedEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @KafkaListener(
            topics = "${pos.invoice.kafka.customer-manifest-topic:customer.manifest.v1}",
            groupId = "${pos.invoice.kafka.customer-manifest-consumer-group:pos-invoice-customer-manifests}")
    public void onManifest(@NonNull String message) {
        ReconciliationManifestV1 manifest;
        try {
            JsonNode envelope = objectMapper.readTree(message);
            manifest = objectMapper.treeToValue(envelope.path("payload"), ReconciliationManifestV1.class);
        } catch (Exception e) {
            // Malformed manifests are dropped, not retried: the next window repeats the check.
            log.warn("Ignoring unparseable reconciliation manifest: {}", message, e);
            return;
        }

        // A manifest published before it carried a tenant was the platform tenant's record.
        UUID tenantId = manifest.tenantIdOr(PlatformTenant.ID);

        List<String> receivedIds = processedEventRepository.findEventIdsInRange(
                CustomerEventsListener.OWNER,
                tenantId,
                UuidV7Timestamps.minStringAt(manifest.windowStartUtc()),
                UuidV7Timestamps.minStringAt(manifest.windowEndUtc()));
        String observedChecksum = ReconciliationManifestV1.checksumOf(receivedIds);

        if (manifest.matches(receivedIds.size(), observedChecksum)) {
            log.debug(
                    "Replica reconciled tenant={} window=[{}, {}) events={}",
                    tenantId,
                    manifest.windowStartUtc(),
                    manifest.windowEndUtc(),
                    manifest.eventCount());
            return;
        }

        countDrift(tenantId);
        log.warn(
                "Replica drift detected owner=customer tenant={} window=[{}, {}) expectedCount={} observedCount={}"
                        + " expectedChecksum={} observedChecksum={} eventTypeCounts={} — requesting outbox replay",
                tenantId,
                manifest.windowStartUtc(),
                manifest.windowEndUtc(),
                manifest.eventCount(),
                receivedIds.size(),
                manifest.eventIdsChecksum(),
                observedChecksum,
                manifest.eventTypeCounts());
        requestReplay(manifest, tenantId);
    }

    /**
     * One {@code replica.drift} increment per mismatched manifest, tagged with the tenant it was
     * published for, so one tenant's divergence is visible on its own.
     */
    private void countDrift(@NonNull UUID tenantId) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("replica.drift")
                .description("Reconciliation manifests that did not match the local replica")
                .tag("owner", "customer")
                .tag("entity", "customer-events")
                .tag("tenant", tenantId.toString())
                .register(meterRegistry)
                .increment();
    }

    private void requestReplay(@NonNull ReconciliationManifestV1 manifest, @NonNull UUID tenantId) {
        try {
            String command = objectMapper.writeValueAsString(new ReplayCommand(
                    REPLAY_COMMAND_TYPE,
                    new ReplayCommand.Payload(
                            manifest.windowStartUtc().toString(),
                            manifest.windowEndUtc().toString())));
            kafkaTemplate.send(TenantKafkaHeaders.record(
                    customerCommandsTopic, manifest.windowStartUtc().toString(), command, tenantId));
        } catch (Exception e) {
            // Best effort: the drift metric already fired, and the next manifest re-detects.
            log.warn("Failed to publish outbox replay request for window starting {}", manifest.windowStartUtc(), e);
        }
    }

    /** Command envelope for the owner's {@code customer.commands.v1} listener. */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
