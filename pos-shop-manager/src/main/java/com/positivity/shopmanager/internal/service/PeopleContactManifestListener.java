package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
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
 * Consumer-side reconciliation for the {@code ext_people_contact_person} replica (ADR-0044 §4,
 * issue #885). Doubles as the bootstrap path: a brand-new empty replica mismatches every manifest
 * window, so replay requests backfill it without dedicated snapshot code.
 *
 * <p>For every {@link ReconciliationManifestV1} on {@code people-contact.manifest.v1}, recomputes
 * the same count + checksum from {@code processed_events} (owner {@code people-contact}). On
 * mismatch it increments {@code replica.drift} and publishes a
 * {@code people-contact.outbox.replay-requested} command for the window; replayed events are
 * deduplicated by the {@code processed_events} primary key, so repair is idempotent.
 *
 * <p>Manifests are per tenant (ADR-0062 §3): the listener runs under the manifest's tenant,
 * compares it against that tenant's ledger rows only, tags the drift metric with the tenant and
 * sends the replay command with the tenant header so the owner replays only that tenant's events.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.shop-manager.kafka", name = "enabled", havingValue = "true")
public class PeopleContactManifestListener {

    private static final String REPLAY_COMMAND_TYPE = "people-contact.outbox.replay-requested";

    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final @Nullable MeterRegistry meterRegistry;

    @Value("${pos.shop-manager.kafka.people-contact-commands-topic:people-contact.commands.v1}")
    private String peopleContactCommandsTopic;

    public PeopleContactManifestListener(
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
            topics = "${pos.shop-manager.kafka.people-contact-manifest-topic:people-contact.manifest.v1}",
            groupId =
                    "${pos.shop-manager.kafka.people-contact-manifest-consumer-group:pos-shop-manager-people-contact-manifests}")
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
                PeopleContactEventsListener.OWNER,
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
                "Replica drift detected owner=people-contact tenant={} window=[{}, {}) expectedCount={} observedCount={}"
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
                .tag("owner", "people-contact")
                .tag("entity", "people-contact-events")
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
                    peopleContactCommandsTopic, manifest.windowStartUtc().toString(), command, manifest.tenantId()));
        } catch (Exception e) {
            // Best effort: the drift metric already fired, and the next manifest re-detects.
            log.warn("Failed to publish outbox replay request for window starting {}", manifest.windowStartUtc(), e);
        }
    }

    /** Command envelope for the owner's {@code people-contact.commands.v1} listener. */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
