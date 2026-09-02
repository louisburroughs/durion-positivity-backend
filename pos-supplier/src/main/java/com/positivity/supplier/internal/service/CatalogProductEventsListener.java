package com.positivity.supplier.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.supplier.internal.entity.ExtProductCodeReplica;
import com.positivity.supplier.internal.entity.ProcessedEvent;
import com.positivity.supplier.internal.repository.ExtProductCodeReplicaRepository;
import com.positivity.supplier.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code catalog.events.v1} into the {@code ext_product_code} replica (ADR-0044 R3).
 *
 * <p>This is the read path PRICAT matching depends on. ADR-0044 R1 forbids pos-supplier calling
 * pos-catalog synchronously, so the identity codes travel here as facts and matching resolves them
 * locally (ADR-0053 §5).
 *
 * <p>Consumer contract mirrors the platform's other catalog consumers: {@code processed_events}
 * idempotency (owner {@code catalog}) written in the apply transaction, a stale guard on the fact's
 * {@code aggregateVersion} that skips only strictly-lower versions, and transient database errors
 * rethrown so the container retries rather than recording the event as processed. The guard is
 * {@link ReplicaVersionGuard} (#1486): pos-catalog's aggregateVersion strictly advances, so equal
 * versions apply rather than skip — both as an idempotent no-op for live traffic and because
 * {@code POST .../facts/replay} depends on it to repair a replica that holds the version number but
 * wrong or missing rows. Unsupported event types still record their eventId, so the owner's
 * manifest reconciles instead of reporting facts this module deliberately ignored as missing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.supplier.kafka", name = "enabled", havingValue = "true")
public class CatalogProductEventsListener {

    /** Producing domain, per the repo-wide processed_events convention. */
    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtProductCodeReplicaRepository replicaRepository;

    @KafkaListener(
            topics = "${pos.supplier.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${pos.supplier.kafka.catalog-events-consumer-group:pos-supplier-catalog-events}")
    @Transactional
    public void onCatalogEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable catalog event", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping catalog event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (ProductUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyProductUpdated(envelope);
            } else {
                log.debug("Ignoring catalog event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries: recording this event as processed would drop a
            // product's codes from the replica permanently, and PRICAT lines would quarantine as
            // unmatched for a reason that has nothing to do with the vendor.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed catalog event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyProductUpdated(JsonNode envelope) {
        ProductUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), ProductUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        UUID productId = payload.productId();

        ExtProductCodeReplica existing = replicaRepository.findById(productId).orElse(null);
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — catalog's
        // aggregateVersion strictly advances, so equal means identical content, and replay resends
        // the held version deliberately to repair a replica with wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            return;
        }

        // A cleared code is persisted as null rather than by deleting the row: "this product has no
        // code" and "this module has never heard of this product" are different answers, and the
        // resolver reports them differently.
        replicaRepository.save(ExtProductCodeReplica.builder()
                .productId(productId)
                .codeType(trimToNull(payload.productCodeType()))
                .code(trimToNull(payload.productCode()))
                // The SKU travels on the same fact and is kept for the same reason the codes are:
                // the availability read resolves a caller's SKU locally (ADR-0044 R1/R3), and a
                // synchronous ask to pos-catalog is forbidden.
                .sku(trimToNull(payload.sku()))
                .aggregateVersion(aggregateVersion)
                .build());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
