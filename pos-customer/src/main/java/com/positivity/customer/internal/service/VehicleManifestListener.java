package com.positivity.customer.internal.service;

import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
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
 * Consumer-side reconciliation for the vehicle replica (ADR-0044 §4, issue #843).
 *
 * <p>For every {@link ReconciliationManifestV1} on {@code vehicle.manifest.v1}, recomputes the
 * same count + checksum from {@code processed_events} (owner {@code vehicle}) — window membership
 * is the UUIDv7 timestamp embedded in each recorded eventId, exactly the definition the owner
 * used. On mismatch it increments {@code replica.drift}
 * (Prometheus: {@code replica_drift_total{owner="vehicle"}}) and publishes a
 * {@code vehicle.outbox.replay-requested} command for the window; the replayed events are
 * deduplicated by the {@code processed_events} primary key, so repair is idempotent.
 *
 * <p>The comparison is stateless: reprocessing a manifest (redelivery, owner re-publish after a
 * restart) yields the same verdict, and a duplicate replay request only causes an idempotent
 * re-delivery. A transient false positive (e.g. consumer still lagging inside the owner's grace
 * period) therefore costs one harmless replay, never corruption.
 *
 * <p>Manifests are per tenant (ADR-0062 §3): the listener runs under the manifest's tenant,
 * compares it against that tenant's ledger rows only, tags the drift metric with the tenant and
 * sends the replay command with the tenant header so the owner replays only that tenant's events.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class VehicleManifestListener {

    private static final String REPLAY_COMMAND_TYPE = "vehicle.outbox.replay-requested";

    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final @Nullable MeterRegistry meterRegistry;

    @Value("${pos.customer.kafka.vehicle-commands-topic:vehicle.commands.v1}")
    private String vehicleCommandsTopic;

    public VehicleManifestListener(
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
            topics = "${pos.customer.kafka.vehicle-manifest-topic:vehicle.manifest.v1}",
            groupId = "${pos.customer.kafka.vehicle-manifest-consumer-group:pos-customer-vehicle-manifests}")
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

        List<String> receivedIds = processedEventRepository.findEventIdsInRange(
                VehicleEventsListener.OWNER,
                manifest.tenantId(),
                UuidV7Timestamps.minStringAt(manifest.windowStartUtc()),
                UuidV7Timestamps.minStringAt(manifest.windowEndUtc()));
        String observedChecksum = ReconciliationManifestV1.checksumOf(receivedIds);

        if (manifest.matches(receivedIds.size(), observedChecksum)) {
            log.debug(
                    "Replica reconciled tenant={} window=[{}, {}) events={}",
                    manifest.tenantId(),
                    manifest.windowStartUtc(),
                    manifest.windowEndUtc(),
                    manifest.eventCount());
            return;
        }

        countDrift(manifest.tenantId());
        log.warn(
                "Replica drift detected owner=vehicle tenant={} window=[{}, {}) expectedCount={} observedCount={}"
                        + " expectedChecksum={} observedChecksum={} eventTypeCounts={} — requesting outbox replay",
                manifest.tenantId(),
                manifest.windowStartUtc(),
                manifest.windowEndUtc(),
                manifest.eventCount(),
                receivedIds.size(),
                manifest.eventIdsChecksum(),
                observedChecksum,
                manifest.eventTypeCounts());
        requestReplay(manifest);
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
                .tag("owner", "vehicle")
                .tag("entity", "vehicle-events")
                .tag("tenant", tenantId.toString())
                .register(meterRegistry)
                .increment();
    }

    private void requestReplay(@NonNull ReconciliationManifestV1 manifest) {
        try {
            String command = objectMapper.writeValueAsString(new ReplayCommand(
                    REPLAY_COMMAND_TYPE,
                    new ReplayCommand.Payload(
                            manifest.windowStartUtc().toString(),
                            manifest.windowEndUtc().toString())));
            kafkaTemplate.send(TenantKafkaHeaders.record(
                    vehicleCommandsTopic, manifest.windowStartUtc().toString(), command, manifest.tenantId()));
        } catch (Exception e) {
            // Best effort: the drift metric already fired, and the next manifest re-detects.
            log.warn("Failed to publish outbox replay request for window starting {}", manifest.windowStartUtc(), e);
        }
    }

    /** Command envelope for the owner's {@code vehicle.commands.v1} listener. */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
