package com.positivity.invoice.internal.service;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
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
 * Consumer-side reconciliation for the {@code ext_people_employee} replica (ADR-0044 §4, #1537).
 *
 * <p>For every {@link ReconciliationManifestV1} on {@code people.manifest.v1}, recomputes the
 * same count + checksum from {@code processed_events} (owner {@code people}) — window membership
 * is the UUIDv7 timestamp embedded in each recorded eventId, exactly the definition the owner
 * used. On mismatch it increments {@code replica.drift} and publishes a
 * {@code people.outbox.replay-requested} command for the window; the replayed events are
 * deduplicated by the {@code processed_events} primary key, so repair is idempotent. Manifests are per
 * tenant (ADR-0062 §3): the listener runs under the manifest\'s tenant, compares it against that
 * tenant\'s ledger rows only, tags the drift metric with the tenant and sends the replay command
 * with the tenant header so the owner replays only that tenant\'s events.
 *
 * <p>Before this listener existed, {@code pos-people} published manifests on
 * {@code people.manifest.v1} that nothing consumed, and {@code PeopleCommandListener} handled
 * {@code people.outbox.replay-requested} commands that nothing sent — both surfaces sat inert.
 * This closes the loop for the employee replica the same way {@code PeopleContactManifestListener}
 * already does for the person-identity replica.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class PeopleManifestListener {

    private static final String REPLAY_COMMAND_TYPE = "people.outbox.replay-requested";

    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final @Nullable MeterRegistry meterRegistry;

    @Value("${pos.invoice.kafka.people-commands-topic:people.commands.v1}")
    private String peopleCommandsTopic;

    public PeopleManifestListener(
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
            topics = "${pos.invoice.kafka.people-manifest-topic:people.manifest.v1}",
            groupId = "${pos.invoice.kafka.people-manifest-consumer-group:pos-invoice-people-manifests}")
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
                    "Skipping reconciliation manifest without tenantId owner=people window=[{}, {}): manifests"
                            + " are per tenant from WS4-3 on",
                    manifest.windowStartUtc(),
                    manifest.windowEndUtc());
            return;
        }

        List<String> receivedIds = processedEventRepository.findEventIdsInRange(
                PeopleEventsListener.OWNER,
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
                "Replica drift detected owner=people tenant={} window=[{}, {}) expectedCount={} observedCount={}"
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
                .tag("owner", "people")
                .tag("entity", "people-events")
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
                .tag("owner", "people")
                .tag("entity", "people-events")
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
                    peopleCommandsTopic, manifest.windowStartUtc().toString(), command, tenantId));
        } catch (Exception e) {
            // Best effort: the drift metric already fired, and the next manifest re-detects.
            log.warn("Failed to publish outbox replay request for window starting {}", manifest.windowStartUtc(), e);
        }
    }

    /** Command envelope for the owner's {@code people.commands.v1} listener. */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
