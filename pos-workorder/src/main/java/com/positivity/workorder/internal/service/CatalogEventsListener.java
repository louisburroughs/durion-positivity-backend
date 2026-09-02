package com.positivity.workorder.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.catalog.CatalogServiceUpdatedV1;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.workorder.internal.entity.ExtCatalogServiceReplica;
import com.positivity.workorder.internal.entity.ExtProductUomReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.workorder.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * Consumes {@code catalog.events.v1} into the {@code ext_product_uom} replica (ADR-0044 §6,
 * ADR-0055, #1413) — the per-product declaration of how divisible a product's stock is, which this
 * module needs to decide whether a part quantity may carry decimals.
 *
 * <p>Only the unit-of-measure set is replicated. pos-inventory and pos-order take more from the
 * same fact (tracking level, substitution groups, product codes); this module has no use for any
 * of it, and replicating data nothing reads is how replicas rot.
 *
 * <p>Consumer contract: {@code processed_events} idempotency (owner {@code catalog}) in the apply
 * transaction, transient DB errors rethrown for container retry/DLQ, malformed payloads logged and
 * dropped rather than poisoning the partition. There is no catalog manifest listener in this
 * module, so — unlike {@code InventoryEventsListener} — facts of other types are skipped without
 * recording a dedup row; nothing compares counts against them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class CatalogEventsListener {

    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtProductUomReplicaRepository productUomReplicaRepository;
    private final ExtCatalogServiceReplicaRepository catalogServiceReplicaRepository;

    @KafkaListener(
            topics = "${workorder.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${workorder.kafka.catalog-events-consumer-group:pos-workorder-catalog-events}")
    @Transactional
    public void onCatalogEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable catalog event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        boolean productFact = ProductUpdatedV1.EVENT_TYPE.equals(eventType);
        boolean serviceFact = CatalogServiceUpdatedV1.EVENT_TYPE.equals(eventType);
        if (!productFact && !serviceFact) {
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping catalog event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (productFact) {
                applyUomConversions(envelope);
            } else {
                applyCatalogService(envelope);
            }
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .owner(OWNER)
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed catalog event eventId={}", eventId, e);
        }
    }

    /**
     * Replaces the product's unit-of-measure set wholesale.
     *
     * <p>Wholesale rather than merged, because each fact carries the product's full set. Merging
     * would leave a UoM catalog has retired still declared here, so a part could go on being judged
     * divisible against a scale its owner no longer publishes.
     *
     * <p>The stale guard compares the fact's {@code aggregateVersion} — pos-catalog's JPA
     * {@code @Version}-backed counter, strictly advancing (seeded from the legacy {@code updatedAt}
     * epoch millis so magnitudes continue seamlessly) — against the highest version this module
     * already holds for the product, using {@link ReplicaVersionGuard} (#1486). It applies on
     * equality rather than skipping: catalog's strictly-advancing contract means an equal version
     * is identical content, and {@code POST .../facts/replay} deliberately resends a fact at the
     * held version to repair a replica that holds the version number but wrong or missing rows —
     * skipping on equal would silently turn replay into a no-op. It is belt-and-braces: facts are
     * keyed by product id, so a partition already delivers one product's facts in order, and what
     * this guards is redelivery after a rebalance. A product that currently holds no rows has
     * nothing to roll backwards, so the guard does not apply and the fact lands.
     */
    /**
     * Upserts the service-taxonomy replica row from a {@code catalog.service.updated} fact
     * (#1569 Phase 1, schema v2). Same stale-guard semantics as the UoM apply below: apply on
     * equal version (replay repairs), skip only strictly-older facts. A delete tombstone lands
     * as {@code active=false} — kept, so a retired service stops resolving without vanishing.
     *
     * <p>The taxonomy fields are schema-v2 additions; on a v1 fact they read as null, which is
     * exactly "the catalog stated nothing" — never zero hours.
     */
    private void applyCatalogService(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID serviceId = UUID.fromString(payload.path("serviceId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        Long held = catalogServiceReplicaRepository
                .findById(serviceId)
                .map(ExtCatalogServiceReplica::getAggregateVersion)
                .orElse(null);
        if (held != null && ReplicaVersionGuard.isStale(held, aggregateVersion)) {
            return;
        }

        JsonNode hoursNode = payload.path("defaultLaborHours");
        catalogServiceReplicaRepository.save(ExtCatalogServiceReplica.builder()
                .serviceId(serviceId)
                .name(payload.path("name").stringValue(null))
                .operationCode(payload.path("operationCode").stringValue(null))
                .operationCategory(payload.path("operationCategory").stringValue(null))
                .defaultLaborHours(hoursNode.isNumber() ? hoursNode.decimalValue() : null)
                .active(payload.path("active").booleanValue(false))
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    private void applyUomConversions(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID productId = UUID.fromString(payload.path("productId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        List<Long> heldVersions = productUomReplicaRepository.findAggregateVersions(productId);
        if (heldVersions.stream().anyMatch(held -> ReplicaVersionGuard.isStale(held, aggregateVersion))) {
            return;
        }

        productUomReplicaRepository.deleteByProductId(productId);
        for (JsonNode conversion : payload.path("uomConversions")) {
            String uomCode = conversion.path("uomCode").stringValue(null);
            if (uomCode == null || uomCode.isBlank()) {
                continue;
            }
            productUomReplicaRepository.save(ExtProductUomReplica.builder()
                    .productId(productId)
                    .uomCode(uomCode)
                    .uomType(conversion.path("uomType").stringValue(null))
                    .factorToBase(conversion.path("factorToBase").decimalValue())
                    .precisionScale(conversion.path("precisionScale").intValue(0))
                    .aggregateVersion(aggregateVersion)
                    .build());
        }
    }
}
