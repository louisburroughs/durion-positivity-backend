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
 * Consumer-side reconciliation for the workorder replica (ADR-0044 §4, issue #840).
 *
 * <p>For every {@link ReconciliationManifestV1} on {@code workorder.manifest.v1}, recomputes the
 * same count + checksum from {@code processed_events} — window membership is the UUIDv7 timestamp
 * embedded in each recorded eventId, exactly the definition the owner used. It must read the same
 * table {@link WorkorderEventsListener} writes: it used to read {@code processing_log}, which only
 * the retired {@code WorkorderEventHandler} ever wrote, so every non-empty window mismatched and
 * asked the owner to replay it (#1584). On mismatch it
 * increments {@code replica.drift} (Prometheus: {@code replica_drift_total{owner="workorder"}})
 * and publishes a {@code workorder.outbox.replay-requested} command for the window; the replayed
 * events are deduplicated by the unique {@code event_id} guard, so repair is idempotent.
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
public class WorkorderManifestListener {

    private static final String REPLAY_COMMAND_TYPE = "workorder.outbox.replay-requested";

    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final @Nullable MeterRegistry meterRegistry;

    @Value("${pos.customer.kafka.workorder-commands-topic:workorder.commands.v1}")
    private String workorderCommandsTopic;

    public WorkorderManifestListener(
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
            topics = "${pos.customer.kafka.workorder-manifest-topic:workorder.manifest.v1}",
            groupId = "${pos.customer.kafka.manifest-consumer-group:pos-customer-workorder-manifests}")
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

        UUID tenantId = manifest.tenantId();
        if (tenantId == null) {
            // Manifests are per tenant from ADR-0062 WS4-3 on. One published before it carried a
            // tenant summarised every tenant's rows at once, which no single tenant's ledger can be
            // compared against, so it is skipped (and counted) rather than misread as one tenant's.
            countManifestSkipped();
            log.warn(
                    "Skipping reconciliation manifest without tenantId owner=workorder window=[{}, {}): manifests"
                            + " are per tenant from WS4-3 on",
                    manifest.windowStartUtc(),
                    manifest.windowEndUtc());
            return;
        }

        List<String> receivedIds = processedEventRepository.findEventIdsInRange(
                WorkorderEventsListener.OWNER,
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
                "Replica drift detected owner=workorder tenant={} window=[{}, {}) expectedCount={} observedCount={}"
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
                .tag("owner", "workorder")
                .tag("entity", "workorder-events")
                .tag("tenant", tenantId.toString())
                .register(meterRegistry)
                .increment();
    }

    /**
     * One {@code replica.manifest.skipped} increment per manifest that carries no tenant (one
     * published before manifests were per tenant), so a consumer still receiving them is visible.
     */
    private void countManifestSkipped() {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("replica.manifest.skipped")
                .description("Reconciliation manifests skipped because they carry no tenant")
                .tag("owner", "workorder")
                .tag("entity", "workorder-events")
                .tag("reason", "missing_tenant")
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
                    workorderCommandsTopic, manifest.windowStartUtc().toString(), command, tenantId));
        } catch (Exception e) {
            // Best effort: the drift metric already fired, and the next manifest re-detects.
            log.warn("Failed to publish outbox replay request for window starting {}", manifest.windowStartUtc(), e);
        }
    }

    /** Command envelope for the owner's {@code workorder.commands.v1} listener. */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
