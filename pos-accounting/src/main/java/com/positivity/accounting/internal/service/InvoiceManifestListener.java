package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.ProcessedEventRepository;
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
 * Consumer-side reconciliation for the {@code ext_invoice} / {@code ext_invoice_tax} replicas
 * (ADR-0044 §4, issue #1537 D2).
 *
 * <p>For every {@link ReconciliationManifestV1} on {@code invoice.manifest.v1}, recomputes the
 * same count + checksum from this module's {@code processed_events} rows — scoped to the {@code
 * invoice} owner tag {@link InvoiceEventsListener} stamps on every row it saves. That scoping is
 * load-bearing: unlike other modules' {@code processed_events}, accounting's table has no {@code
 * owner} column of its own baseline and is shared by every one of this module's Kafka listeners
 * (warranty/order/inventory/customer/settlement/supplier-invoice events all write rows here too).
 * pos-invoice's {@code ManifestPublisher} computes its manifest checksum only over {@code
 * invoice.events.v1} eventIds, so an unscoped window scan here would pull in every other
 * listener's eventIds and the comparison would drift permanently even on a perfectly intact
 * replica — {@link InvoiceEventsListener#OWNER} exists precisely to keep this comparison exact.
 *
 * <p>On mismatch it increments {@code replica.drift}
 * (Prometheus: {@code replica_drift_total{owner="invoice"}}) and publishes an
 * {@code invoice.outbox.replay-requested} command for the window; the replayed events are
 * deduplicated by the {@code processed_events} primary key, so repair is idempotent.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class InvoiceManifestListener {

    private static final String REPLAY_COMMAND_TYPE = "invoice.outbox.replay-requested";

    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Counter driftCounter;

    @Value("${pos.accounting.kafka.invoice-commands-topic:invoice.commands.v1}")
    private String invoiceCommandsTopic;

    public InvoiceManifestListener(
            ProcessedEventRepository processedEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.processedEventRepository = processedEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.driftCounter = registry == null
                ? null
                : Counter.builder("replica.drift")
                        .description("Reconciliation manifests that did not match the local replica")
                        .tag("owner", InvoiceEventsListener.OWNER)
                        .tag("entity", "invoice-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.invoice-manifest-topic:invoice.manifest.v1}",
            groupId = "${pos.accounting.kafka.invoice-manifest-consumer-group:pos-accounting-invoice-manifests}")
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

        List<String> receivedIds = processedEventRepository.findEventIdsInRangeForOwner(
                InvoiceEventsListener.OWNER,
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
                "Replica drift detected owner=invoice window=[{}, {}) expectedCount={} observedCount={}"
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
                    new ReplayCommand.Payload(
                            manifest.windowStartUtc().toString(),
                            manifest.windowEndUtc().toString())));
            kafkaTemplate.send(invoiceCommandsTopic, manifest.windowStartUtc().toString(), command);
        } catch (Exception e) {
            // Best effort: the drift metric already fired, and the next manifest re-detects.
            log.warn("Failed to publish outbox replay request for window starting {}", manifest.windowStartUtc(), e);
        }
    }

    /** Command envelope for pos-invoice's {@code invoice.commands.v1} listener. */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
