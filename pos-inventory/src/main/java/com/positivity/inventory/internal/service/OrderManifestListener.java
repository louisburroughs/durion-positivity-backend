package com.positivity.inventory.internal.service;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
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
 * Consumer-side reconciliation for this module's two {@code order.events.v1} consumers (ADR-0044 §4, #2596):
 * the counter-sale stock postings ({@link OrderEventsListener}, owner {@value OrderEventsListener#OWNER}) and the
 * purchase-order projection ({@link PurchaseOrderProjectionListener}, owner {@value
 * PurchaseOrderProjectionListener#OWNER}).
 *
 * <p>Each consumer keeps its own {@code processed_events} rows under its own owner and reads the topic in its own
 * consumer group (#2176), so each is reconciled on its own: one {@link KafkaListener} per owner, each in a
 * consumer group of its own, so every manifest reaches both checks. For every {@link ReconciliationManifestV1} on
 * {@code order.manifest.v1}, a check recomputes the same count + checksum from its owner's rows — window
 * membership is the UUIDv7 timestamp embedded in each recorded eventId, exactly the definition pos-order's {@code
 * ManifestPublisher} used. Both consumers record every eventId they read, ignored fact types included, so a type
 * they do not apply never shows as permanent drift. On mismatch the check increments {@code replica.drift} and
 * publishes an {@code order.outbox.replay-requested} command for the window on {@code order.commands.v1}; the
 * replayed events are deduplicated by the {@code processed_events} key, so repair is idempotent, and a replay one
 * check asked for is harmless to the other.
 *
 * <p>Manifests are per tenant (ADR-0062 §3): a check runs under the manifest's tenant, compares it against that
 * tenant's ledger rows only, tags the drift metric with the tenant and sends the replay command with the tenant
 * header so the owner replays only that tenant's events.
 */
@Slf4j
@Component
@KafkaRails
public class OrderManifestListener {

    private static final String REPLAY_COMMAND_TYPE = "order.outbox.replay-requested";

    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final @Nullable MeterRegistry meterRegistry;

    @Value("${pos.inventory.kafka.order-commands-topic:order.commands.v1}")
    private String orderCommandsTopic;

    public OrderManifestListener(
            ProcessedEventRepository processedEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.processedEventRepository = processedEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /** The counter-sale consumer's check ({@link OrderEventsListener}). */
    @KafkaListener(
            topics = "${pos.inventory.kafka.order-manifest-topic:order.manifest.v1}",
            groupId = "${pos.inventory.kafka.order-manifest-consumer-group:pos-inventory-order-manifests}")
    public void onOrderEventsManifest(@NonNull String message) {
        reconcile(message, OrderEventsListener.OWNER, "order-events");
    }

    /** The purchase-order projection's check ({@link PurchaseOrderProjectionListener}). */
    @KafkaListener(
            topics = "${pos.inventory.kafka.order-manifest-topic:order.manifest.v1}",
            groupId =
                    "${pos.inventory.kafka.purchase-order-projection-manifest-consumer-group:pos-inventory-purchase-order-projection-manifests}")
    public void onPurchaseOrderProjectionManifest(@NonNull String message) {
        reconcile(message, PurchaseOrderProjectionListener.OWNER, "purchase-order-projection");
    }

    private void reconcile(@NonNull String message, @NonNull String owner, @NonNull String entity) {
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
            countManifestSkipped(owner, entity);
            log.warn(
                    "Skipping reconciliation manifest without tenantId owner={} window=[{}, {}): manifests are"
                            + " per tenant",
                    owner,
                    manifest.windowStartUtc(),
                    manifest.windowEndUtc());
            return;
        }

        List<String> receivedIds = processedEventRepository.findEventIdsInRange(
                owner,
                tenantId,
                UuidV7Timestamps.minStringAt(manifest.windowStartUtc()),
                UuidV7Timestamps.minStringAt(manifest.windowEndUtc()));
        String observedChecksum = ReconciliationManifestV1.checksumOf(receivedIds);

        if (manifest.matches(receivedIds.size(), observedChecksum)) {
            log.debug(
                    "Replica reconciled owner={} tenant={} window=[{}, {}) events={}",
                    owner,
                    tenantId,
                    manifest.windowStartUtc(),
                    manifest.windowEndUtc(),
                    manifest.eventCount());
            return;
        }

        countDrift(owner, entity, tenantId);
        log.warn(
                "Replica drift detected owner={} tenant={} window=[{}, {}) expectedCount={} observedCount={}"
                        + " expectedChecksum={} observedChecksum={} eventTypeCounts={} — requesting outbox replay",
                owner,
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
     * One {@code replica.drift} increment per mismatched manifest, tagged with the consumer's owner and the
     * tenant it was published for, so one consumer's or one tenant's divergence is visible on its own.
     */
    private void countDrift(@NonNull String owner, @NonNull String entity, @NonNull UUID tenantId) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("replica.drift")
                .description("Reconciliation manifests that did not match the local replica")
                .tag("owner", owner)
                .tag("entity", entity)
                .tag("tenant", tenantId.toString())
                .register(meterRegistry)
                .increment();
    }

    /** One {@code replica.manifest.skipped} increment per manifest that carries no tenant. */
    private void countManifestSkipped(@NonNull String owner, @NonNull String entity) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("replica.manifest.skipped")
                .description("Reconciliation manifests skipped because they carry no tenant")
                .tag("owner", owner)
                .tag("entity", entity)
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
                        orderCommandsTopic, manifest.windowStartUtc().toString(), command, tenantId),
                manifest.windowStartUtc());
    }

    /** Command envelope for pos-order's {@code order.commands.v1} listener. */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
