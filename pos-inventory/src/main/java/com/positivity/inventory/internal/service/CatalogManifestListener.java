package com.positivity.inventory.internal.service;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer-side reconciliation for the inventory catalog product replicas (odoo-parity B1, #1033;
 * ADR-0044 §4).
 *
 * <p>For every {@link ReconciliationManifestV1} on {@code catalog.manifest.v1}, recomputes the
 * same count + checksum from {@code processed_events} (owner {@code catalog}) — window membership
 * is the UUIDv7 timestamp embedded in each recorded eventId, exactly the definition the owner
 * used. On mismatch it increments {@code replica.drift}.
 *
 * <p>Unlike {@link LocationManifestListener}, drift does NOT publish a replay command:
 * pos-catalog has no {@code catalog.commands.v1} listener yet (X1 finding, #1023) — a command
 * would be dropped unheard. Detection stays log + metric until catalog grows a replay listener;
 * the drift counter is the operational signal to re-emit catalog-side.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.inventory.kafka", name = "enabled", havingValue = "true")
public class CatalogManifestListener {

    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;
    private final Counter driftCounter;

    public CatalogManifestListener(
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.driftCounter = registry == null
                ? null
                : Counter.builder("replica.drift")
                        .description("Reconciliation manifests that did not match the local replica")
                        .tag("owner", "catalog")
                        .tag("entity", "catalog-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.inventory.kafka.catalog-manifest-topic:catalog.manifest.v1}",
            groupId = "${pos.inventory.kafka.catalog-manifest-consumer-group:pos-inventory-catalog-manifests}")
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
                CatalogEventsListener.OWNER,
                UuidV7Timestamps.minStringAt(manifest.windowStartUtc()),
                UuidV7Timestamps.minStringAt(manifest.windowEndUtc()));
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
                "Replica drift detected owner=catalog window=[{}, {}) expectedCount={} observedCount={}"
                        + " expectedChecksum={} observedChecksum={} eventTypeCounts={} — no replay requested:"
                        + " catalog has no catalog.commands.v1 listener yet (#1023); repair requires a"
                        + " catalog-side re-emit",
                manifest.windowStartUtc(),
                manifest.windowEndUtc(),
                manifest.eventCount(),
                receivedIds.size(),
                manifest.eventIdsChecksum(),
                observedChecksum,
                manifest.eventTypeCounts());
    }
}
