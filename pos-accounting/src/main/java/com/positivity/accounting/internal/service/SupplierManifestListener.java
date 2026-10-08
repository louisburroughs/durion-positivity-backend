package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.kafka.common.KafkaRails;
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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer-side reconciliation for this module's {@code supplier.events.v1} feed (ADR-0044 §4; CAP:550 S24, #2517):
 * the vendor bills, holds and vendor copy {@link SupplierEventsListener} keeps.
 *
 * <p>For every {@link ReconciliationManifestV1} on {@code supplier.manifest.v1}, recomputes the same count + checksum
 * from this module's {@code processed_events} rows, scoped to the {@code supplier} owner tag {@link
 * SupplierEventsListener} stamps on every row it saves, whatever the event type (the table is shared by every listener
 * of this module, see {@link InvoiceManifestListener}). Window membership is the UUIDv7 timestamp embedded in each
 * recorded eventId, the definition pos-supplier's {@code ManifestPublisher} uses. On mismatch it increments {@code
 * replica.drift{owner="supplier"}} and sends a {@code supplier.outbox.replay-requested} command for the window on
 * {@code supplier.commands.v1}; the replayed events are deduplicated by the {@code processed_events} primary key, so
 * repair is idempotent.
 *
 * <p>Manifests are per tenant (ADR-0062 §3): the listener runs under the manifest's tenant, compares it against that
 * tenant's ledger rows only, tags the drift metric with the tenant and sends the replay command with the tenant header
 * so pos-supplier replays only that tenant's events. A manifest without a tenant is skipped and counted (ADR-0044,
 * 2026-09-09).
 */
@Slf4j
@Component
@KafkaRails
public class SupplierManifestListener {

    private static final String REPLAY_COMMAND_TYPE = "supplier.outbox.replay-requested";

    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final @Nullable MeterRegistry meterRegistry;

    @Value("${pos.accounting.kafka.supplier-commands-topic:supplier.commands.v1}")
    private String supplierCommandsTopic;

    public SupplierManifestListener(
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
            topics = "${pos.accounting.kafka.supplier-manifest-topic:supplier.manifest.v1}",
            groupId = "${pos.accounting.kafka.supplier-manifest-consumer-group:pos-accounting-supplier-manifests}")
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
            // A manifest without a tenant summarises no single tenant's ledger, so it is skipped (and
            // counted) rather than misread as one tenant's (ADR-0044, 2026-09-09 amendment).
            countManifestSkipped();
            log.warn(
                    "Skipping reconciliation manifest without tenantId owner={} window=[{}, {}): manifests are"
                            + " per tenant",
                    SupplierEventsListener.OWNER,
                    manifest.windowStartUtc(),
                    manifest.windowEndUtc());
            return;
        }

        List<String> receivedIds = processedEventRepository.findEventIdsInRangeForOwner(
                SupplierEventsListener.OWNER,
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
                "Replica drift detected owner=supplier tenant={} window=[{}, {}) expectedCount={} observedCount={}"
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
     * One {@code replica.drift} increment per mismatched manifest, tagged with the tenant it was published
     * for, so one tenant's divergence is visible on its own.
     */
    private void countDrift(@NonNull UUID tenantId) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("replica.drift")
                .description("Reconciliation manifests that did not match the local replica")
                .tag("owner", SupplierEventsListener.OWNER)
                .tag("entity", "supplier-events")
                .tag("tenant", tenantId.toString())
                .register(meterRegistry)
                .increment();
    }

    /** One {@code replica.manifest.skipped} increment per manifest that carries no tenant. */
    private void countManifestSkipped() {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("replica.manifest.skipped")
                .description("Reconciliation manifests skipped because they carry no tenant")
                .tag("owner", SupplierEventsListener.OWNER)
                .tag("entity", "supplier-events")
                .tag("reason", "missing_tenant")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Sends the replay request and waits for the broker to take it. A failure propagates for container
     * redelivery (#2452): the owner publishes each window's manifest once, so no later manifest would
     * re-detect this window's drift.
     */
    private void requestReplay(@NonNull ReconciliationManifestV1 manifest, @NonNull UUID tenantId) {
        String command = objectMapper.writeValueAsString(new ReplayCommand(
                REPLAY_COMMAND_TYPE,
                new ReplayCommand.Payload(
                        manifest.windowStartUtc().toString(),
                        manifest.windowEndUtc().toString())));
        OutboxReplayRequests.send(
                kafkaTemplate,
                TenantKafkaHeaders.record(
                        supplierCommandsTopic, manifest.windowStartUtc().toString(), command, tenantId),
                manifest.windowStartUtc());
    }

    /** Command envelope for pos-supplier's {@code supplier.commands.v1} listener ({@code SupplierCommandListener}). */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
