package com.positivity.customer.internal.service;

import com.positivity.customer.internal.enums.ProcessingStatus;
import com.positivity.customer.internal.repository.ProcessingLogRepository;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
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
 * same count + checksum from {@code processing_log} — window membership is the UUIDv7 timestamp
 * embedded in each recorded eventId, exactly the definition the owner used. On mismatch it
 * increments {@code replica.drift} (Prometheus: {@code replica_drift_total{owner="workorder"}})
 * and publishes a {@code workorder.outbox.replay-requested} command for the window; the replayed
 * events are deduplicated by the unique {@code event_id} guard, so repair is idempotent.
 *
 * <p>The comparison is stateless: reprocessing a manifest (redelivery, owner re-publish after a
 * restart) yields the same verdict, and a duplicate replay request only causes an idempotent
 * re-delivery. A transient false positive (e.g. consumer still lagging inside the owner's grace
 * period) therefore costs one harmless replay, never corruption.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class WorkorderManifestListener {

    private static final String REPLAY_COMMAND_TYPE = "workorder.outbox.replay-requested";

    private final ProcessingLogRepository processingLogRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Counter driftCounter;

    @Value("${pos.customer.kafka.workorder-commands-topic:workorder.commands.v1}")
    private String workorderCommandsTopic;

    public WorkorderManifestListener(
            ProcessingLogRepository processingLogRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.processingLogRepository = processingLogRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.driftCounter = registry == null
                ? null
                : Counter.builder("replica.drift")
                        .description("Reconciliation manifests that did not match the local replica")
                        .tag("owner", "workorder")
                        .tag("entity", "workorder-events")
                        .register(registry);
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

        List<String> receivedIds = processingLogRepository.findEventIdsInRange(
                UuidV7Timestamps.minStringAt(manifest.windowStartUtc()),
                UuidV7Timestamps.minStringAt(manifest.windowEndUtc()),
                ProcessingStatus.SKIPPED_DUPLICATE);
        String observedChecksum = ReconciliationManifestV1.checksumOf(receivedIds);

        if (manifest.matches(receivedIds.size(), observedChecksum)) {
            log.debug(
                    "Replica reconciled window=[{}, {}) events={}",
                    manifest.windowStartUtc(),
                    manifest.windowEndUtc(),
                    manifest.eventCount());
            return;
        }

        if (driftCounter != null) {
            driftCounter.increment();
        }
        log.warn(
                "Replica drift detected owner=workorder window=[{}, {}) expectedCount={} observedCount={}"
                        + " expectedChecksum={} observedChecksum={} eventTypeCounts={} — requesting outbox replay",
                manifest.windowStartUtc(),
                manifest.windowEndUtc(),
                manifest.eventCount(),
                receivedIds.size(),
                manifest.eventIdsChecksum(),
                observedChecksum,
                manifest.eventTypeCounts());
        requestReplay(manifest);
    }

    private void requestReplay(@NonNull ReconciliationManifestV1 manifest) {
        try {
            String command = objectMapper.writeValueAsString(new ReplayCommand(
                    REPLAY_COMMAND_TYPE,
                    new ReplayCommand.Payload(manifest.windowStartUtc().toString())));
            kafkaTemplate.send(workorderCommandsTopic, manifest.windowStartUtc().toString(), command);
        } catch (Exception e) {
            // Best effort: the drift metric already fired, and the next manifest re-detects.
            log.warn("Failed to publish outbox replay request for window starting {}", manifest.windowStartUtc(), e);
        }
    }

    /** Command envelope for the owner's {@code workorder.commands.v1} listener. */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since) {}
    }
}
