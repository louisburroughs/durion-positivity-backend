package com.positivity.marketing.internal.service;

import com.positivity.domainevents.catalog.CatalogServiceUpdatedV1;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.marketing.internal.entity.ExtCatalogReplica;
import com.positivity.marketing.internal.entity.ProcessedEvent;
import com.positivity.marketing.internal.enums.CatalogItemKind;
import com.positivity.marketing.internal.repository.ExtCatalogReplicaRepository;
import com.positivity.marketing.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code catalog.events.v1} into the {@code ext_catalog} replica (ADR-0044 §6, #1306).
 *
 * <p>Exists so {@link CampaignReferenceValidator} can resolve a campaign's {@code catalogFocusRef}
 * — a reference to something in another domain, checked at schedule time because that is the last
 * moment a campaign advertising a service that does not exist can still be fixed. ADR-0044 R1
 * permits no synchronous read into pos-catalog, so the answer has to be local.
 *
 * <p>Both catalog facts are replicated, not only the service one this module needed: {@code sku:}
 * and {@code category:} references resolve against product rows, and a consumer that subscribed to
 * the topic for one event type and ignored the other would leave three of the four reference kinds
 * unresolvable for no reason.
 *
 * <p>Consumer contract mirrors pos-warranty's listener on the same topic: {@code processed_events}
 * idempotency (owner {@code catalog}) written in the apply transaction, a stale guard on the fact's
 * {@code aggregateVersion} so an out-of-order redelivery cannot undo a newer fact, and transient DB
 * errors rethrown so the container retries rather than silently dropping the update.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.marketing.kafka", name = "enabled", havingValue = "true")
public class CatalogEventsListener {

    /** Producing domain, per the repo-wide processed_events convention. */
    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCatalogReplicaRepository catalogReplicaRepository;

    public CatalogEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCatalogReplicaRepository catalogReplicaRepository) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.catalogReplicaRepository = catalogReplicaRepository;
    }

    @KafkaListener(
            topics = "${pos.marketing.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${pos.marketing.kafka.catalog-events-consumer-group:pos-marketing-catalog-events}")
    @Transactional
    public void onCatalogEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (RuntimeException e) {
            log.warn("Skipping unparsable catalog event", e);
            return;
        }
        String eventId = envelope.path("eventId").asString("");
        String eventType = envelope.path("eventType").asString("");
        if (eventId.isBlank() || processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate or unidentified catalog event type={} id={}", eventType, eventId);
            return;
        }

        switch (eventType) {
            case ProductUpdatedV1.EVENT_TYPE -> applyProductUpdated(envelope);
            case CatalogServiceUpdatedV1.EVENT_TYPE -> applyServiceUpdated(envelope);
            default -> {
                // The rest of the catalog's facts are none of this module's business; skip
                // without recording them so the log stays scoped to what was actually applied.
                return;
            }
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyProductUpdated(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID productId = optionalUuid(payload, "productId");
        if (productId == null) {
            log.warn("Product fact missing productId; ignoring");
            return;
        }
        upsert(ExtCatalogReplica.builder()
                .catalogItemId(productId)
                .itemKind(CatalogItemKind.PRODUCT)
                .name(payload.path("name").asString(null))
                .sku(payload.path("sku").asString(null))
                .categoryId(optionalUuid(payload, "categoryId"))
                .category(payload.path("category").asString(null))
                .active(payload.path("active").asBoolean(false))
                .aggregateVersion(aggregateVersion(envelope))
                .updatedAt(Instant.now(clock))
                .build());
    }

    /**
     * Apply a service fact.
     *
     * <p>A service carries no sku, category, or any other product attribute; those columns stay
     * null rather than being borrowed for something else, so a {@code sku:} reference can never
     * accidentally resolve to a service.
     */
    private void applyServiceUpdated(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID serviceId = optionalUuid(payload, "serviceId");
        if (serviceId == null) {
            log.warn("Service fact missing serviceId; ignoring");
            return;
        }
        upsert(ExtCatalogReplica.builder()
                .catalogItemId(serviceId)
                .itemKind(CatalogItemKind.SERVICE)
                .name(payload.path("name").asString(null))
                .active(payload.path("active").asBoolean(false))
                .aggregateVersion(aggregateVersion(envelope))
                .updatedAt(Instant.now(clock))
                .build());
    }

    /**
     * Write the row unless a newer fact for the same item is already held.
     *
     * <p>Nothing here is caught: a database failure has to reach the container so the envelope is
     * retried or dead-lettered. Swallowing one would leave the replica quietly behind, and a
     * reference this module cannot resolve blocks a campaign from being scheduled.
     */
    private void upsert(ExtCatalogReplica row) {
        ExtCatalogReplica existing =
                catalogReplicaRepository.findById(row.getCatalogItemId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > row.getAggregateVersion()) {
            log.debug(
                    "Ignoring stale catalog fact for {} (held {} > incoming {})",
                    row.getCatalogItemId(),
                    existing.getAggregateVersion(),
                    row.getAggregateVersion());
            return;
        }
        catalogReplicaRepository.save(row);
        log.debug(
                "Updated ext_catalog {} {} version={}",
                row.getItemKind(),
                row.getCatalogItemId(),
                row.getAggregateVersion());
    }

    private static long aggregateVersion(JsonNode envelope) {
        return envelope.path("aggregateVersion").asLong(0L);
    }

    private static UUID optionalUuid(JsonNode payload, String field) {
        String value = payload.path(field).asString(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
