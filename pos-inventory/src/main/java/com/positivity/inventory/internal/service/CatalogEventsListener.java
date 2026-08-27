package com.positivity.inventory.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.inventory.internal.entity.ExtProductCodeReplica;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.ExtProductSubstitutionReplica;
import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.repository.ExtProductCodeReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductSubstitutionReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code catalog.events.v1} into the {@code ext_product} / {@code ext_product_uom} /
 * {@code ext_product_substitution} replicas (odoo-parity B1, #1033; contract X1, #1023). One
 * listener maintains every schema-v2 product field — base UoM + conversion set for the
 * {@link UomConversionService}, tracking level for E1, substitution-group membership for G2, and
 * category + subcategory for category-based putaway matching (#1514) — so the later stories
 * consume the replica instead of adding further catalog consumers.
 *
 * <p>Consumer contract mirrors the module's other listeners: {@code processed_events} idempotency
 * (owner {@code catalog}) in the apply transaction, stale guard on the fact's
 * {@code aggregateVersion}, transient DB errors rethrown for container retry/DLQ. Unsupported
 * event types still record their eventIds so the owner's manifest reconciles.
 *
 * <p>Two catalog-specific contract points (X1 producer guidance):
 *
 * <ul>
 *   <li>{@code aggregateVersion} is pos-catalog's strictly-advancing {@code @Version} counter
 *       (seeded from the legacy {@code updatedAt} epoch millis, so magnitudes continue seamlessly);
 *       the guard is {@link ReplicaVersionGuard} (#1486) and skips ONLY strictly-lower versions —
 *       equal versions apply, both because group-membership changes can fan out multiple facts at
 *       the same version and because {@code POST .../facts/replay} depends on equal-applies to
 *       repair a replica that holds the version number but wrong or missing rows.
 *   <li>Null tolerance for pre-v2 facts: {@code trackingLevel} null ⇒ {@code NONE};
 *       {@code uomConversions} / {@code substitutionProductIds} null ⇒ empty (children cleared —
 *       the fact carries the full sets, replace wholesale).
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.inventory.kafka", name = "enabled", havingValue = "true")
public class CatalogEventsListener {

    /** Producing domain, per the repo-wide processed_events convention (manifest scans key on it). */
    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtProductReplicaRepository extProductReplicaRepository;
    private final ExtProductCodeReplicaRepository extProductCodeReplicaRepository;
    private final ExtProductUomReplicaRepository extProductUomReplicaRepository;
    private final ExtProductSubstitutionReplicaRepository extProductSubstitutionReplicaRepository;
    private final Counter payloadRejectedCounter;

    public CatalogEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtProductReplicaRepository extProductReplicaRepository,
            ExtProductCodeReplicaRepository extProductCodeReplicaRepository,
            ExtProductUomReplicaRepository extProductUomReplicaRepository,
            ExtProductSubstitutionReplicaRepository extProductSubstitutionReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extProductReplicaRepository = extProductReplicaRepository;
        this.extProductCodeReplicaRepository = extProductCodeReplicaRepository;
        this.extProductUomReplicaRepository = extProductUomReplicaRepository;
        this.extProductSubstitutionReplicaRepository = extProductSubstitutionReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "catalog-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.inventory.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${pos.inventory.kafka.catalog-events-consumer-group:pos-inventory-catalog-events}")
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
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping catalog event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (ProductUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyProductUpdated(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring catalog event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed catalog event payload eventId={}: {}", eventId, e.getMessage(), e);
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
        ExtProductReplica existing =
                extProductReplicaRepository.findById(productId).orElse(null);
        // Strictly-lower-only skip: equal versions APPLY (#1486, ReplicaVersionGuard). Catalog's
        // aggregateVersion strictly advances; equal means identical content, and replay relies on
        // equal-applies to repair a replica that holds the version number but wrong/missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            return;
        }

        extProductReplicaRepository.save(ExtProductReplica.builder()
                .productId(productId)
                .baseUom(payload.baseUom())
                .trackingLevel(
                        payload.trackingLevel() == null
                                ? ExtProductReplica.TRACKING_LEVEL_NONE
                                : payload.trackingLevel())
                .substitutionGroupId(payload.substitutionGroupId())
                // Category + subcategory (#1514): the item half of a putaway match. Written on
                // every fact, so a recategorisation overwrites rather than merges — and a product
                // whose category was cleared upstream ends up with nulls here, not a stale one.
                .categoryId(payload.categoryId())
                .categoryName(trimToNull(payload.category()))
                .subcategoryId(payload.subcategoryId())
                .subcategoryName(trimToNull(payload.subcategory()))
                .aggregateVersion(aggregateVersion)
                .build());

        // The fact carries the product's full UoM conversion set — replace, don't merge.
        extProductUomReplicaRepository.deleteByProductId(productId);
        List<ProductUpdatedV1.UomConversion> conversions =
                payload.uomConversions() == null ? List.of() : payload.uomConversions();
        conversions.forEach(conversion -> extProductUomReplicaRepository.save(ExtProductUomReplica.builder()
                .productId(productId)
                .uomCode(conversion.uomCode())
                .uomType(conversion.uomType())
                .factorToBase(conversion.factorToBase())
                .precisionScale(conversion.precisionScale())
                .build()));

        // Product identity codes (CAP-322 #1312, ADR-0044 R3): the read path supplier stock-hint
        // resolution uses in place of calling pos-catalog, which R1 forbids. A cleared code is
        // stored as null rather than by deleting the row, so "carries no code" stays distinct from
        // "never replicated" — the resolver defers on the latter and gives up on the former.
        extProductCodeReplicaRepository.save(ExtProductCodeReplica.builder()
                .productId(productId)
                .codeType(trimToNull(payload.productCodeType()))
                .code(trimToNull(payload.productCode()))
                .aggregateVersion(aggregateVersion)
                .build());

        // Same for the substitution member set: null means the product is in no group — clear.
        extProductSubstitutionReplicaRepository.deleteByProductId(productId);
        List<UUID> members = payload.substitutionProductIds() == null ? List.of() : payload.substitutionProductIds();
        members.forEach(memberId -> extProductSubstitutionReplicaRepository.save(ExtProductSubstitutionReplica.builder()
                .productId(productId)
                .memberProductId(memberId)
                .build()));

        log.info(
                "Updated ext_product productId={} version={} uomRows={} substitutionMembers={}",
                productId,
                aggregateVersion,
                conversions.size(),
                members.size());
    }

    /** Blank and absent are the same statement from catalog: the product carries no code. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
