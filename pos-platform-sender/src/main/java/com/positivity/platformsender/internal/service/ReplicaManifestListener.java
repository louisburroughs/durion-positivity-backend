package com.positivity.platformsender.internal.service;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.platformsender.internal.repository.ProcessedEventRepository;
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
 * Consumer-side reconciliation for both replicas (ADR-0044 §4), and their bootstrap path: a new,
 * empty replica mismatches every manifest window, so the replay requests it sends backfill it.
 *
 * <p>For every {@link ReconciliationManifestV1} on {@code customer.manifest.v1} or
 * {@code people-contact.manifest.v1}, recomputes the same count and checksum from
 * {@code processed_events} for that owner. On a mismatch it increments {@code replica.drift} and
 * sends the owner's {@code {owner}.outbox.replay-requested} command for the window; replayed events
 * are deduplicated by the {@code processed_events} key, so repair is idempotent.
 *
 * <p>Manifests are per tenant (ADR-0062 §3): each is compared against that tenant's ledger rows
 * only, the drift metric is tagged with the tenant, and the replay command carries the tenant header
 * so the owner replays only that tenant's events. The owner's replay reaches back at most its
 * configured {@code outbox.replay.max-lookback}; a person or party last changed before that is not
 * recoverable by replay and needs the owner's re-emit (see the module README).
 */
@Slf4j
@Component
@KafkaRails
public class ReplicaManifestListener {

    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final @Nullable MeterRegistry meterRegistry;

    @Value("${pos.platform-sender.kafka.customer-commands-topic:customer.commands.v1}")
    private String customerCommandsTopic;

    @Value("${pos.platform-sender.kafka.people-contact-commands-topic:people-contact.commands.v1}")
    private String peopleContactCommandsTopic;

    public ReplicaManifestListener(
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
            topics = "${pos.platform-sender.kafka.customer-manifest-topic:customer.manifest.v1}",
            groupId =
                    "${pos.platform-sender.kafka.customer-manifest-consumer-group:pos-platform-sender-customer-manifests}")
    public void onCustomerManifest(@NonNull String message) {
        reconcile(message, CustomerEventsListener.OWNER, "customer-events", customerCommandsTopic);
    }

    @KafkaListener(
            topics = "${pos.platform-sender.kafka.people-contact-manifest-topic:people-contact.manifest.v1}",
            groupId =
                    "${pos.platform-sender.kafka.people-contact-manifest-consumer-group:pos-platform-sender-people-contact-manifests}")
    public void onPeopleContactManifest(@NonNull String message) {
        reconcile(message, PeopleContactEventsListener.OWNER, "people-contact-events", peopleContactCommandsTopic);
    }

    private void reconcile(
            @NonNull String message, @NonNull String owner, @NonNull String entity, @NonNull String commandsTopic) {
        ReconciliationManifestV1 manifest;
        try {
            JsonNode envelope = objectMapper.readTree(message);
            manifest = objectMapper.treeToValue(envelope.path("payload"), ReconciliationManifestV1.class);
        } catch (Exception e) {
            // Malformed manifests are dropped, not retried: the next window repeats the check.
            log.warn("Ignoring unparseable {} reconciliation manifest: {}", owner, message, e);
            return;
        }

        UUID tenantId = manifest.tenantId();
        if (tenantId == null) {
            // Manifests are per tenant from ADR-0062 WS4-3 on; one without a tenant summarised every
            // tenant at once and cannot be compared against one tenant's ledger.
            count(
                    "replica.manifest.skipped",
                    "Reconciliation manifests skipped because they carry no tenant",
                    owner,
                    entity,
                    "reason",
                    "missing_tenant");
            log.warn(
                    "Skipping reconciliation manifest without tenantId owner={} window=[{}, {})",
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

        count(
                "replica.drift",
                "Reconciliation manifests that did not match the local replica",
                owner,
                entity,
                "tenant",
                tenantId.toString());
        log.warn(
                "Replica drift detected owner={} tenant={} window=[{}, {}) expectedCount={} observedCount={}"
                        + " — requesting outbox replay",
                owner,
                tenantId,
                manifest.windowStartUtc(),
                manifest.windowEndUtc(),
                manifest.eventCount(),
                receivedIds.size());
        requestReplay(manifest, tenantId, owner, commandsTopic);
    }

    /**
     * Sends the replay request and waits for the broker to take it. A failure propagates for
     * container redelivery (#2452): the owner publishes each window's manifest once, so no later
     * manifest would re-detect this window's drift.
     */
    private void requestReplay(
            @NonNull ReconciliationManifestV1 manifest,
            @NonNull UUID tenantId,
            @NonNull String owner,
            @NonNull String commandsTopic) {
        String command = objectMapper.writeValueAsString(new ReplayCommand(
                owner + ".outbox.replay-requested",
                new ReplayCommand.Payload(
                        manifest.windowStartUtc().toString(),
                        manifest.windowEndUtc().toString())));
        OutboxReplayRequests.send(
                kafkaTemplate,
                TenantKafkaHeaders.record(
                        commandsTopic, manifest.windowStartUtc().toString(), command, tenantId),
                manifest.windowStartUtc());
    }

    private void count(String name, String description, String owner, String entity, String tagKey, String tagValue) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(name)
                .description(description)
                .tag("owner", owner)
                .tag("entity", entity)
                .tag(tagKey, tagValue)
                .register(meterRegistry)
                .increment();
    }

    /** Command envelope for the owner's {@code {owner}.commands.v1} listener. */
    record ReplayCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
