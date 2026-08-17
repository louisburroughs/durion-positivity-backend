package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import com.positivity.catalog.internal.entity.TreadDesignTextEntity;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.SupplierPriceEntryRepository;
import com.positivity.catalog.internal.repository.TreadDesignImageRepository;
import com.positivity.catalog.internal.repository.TreadDesignRepository;
import com.positivity.catalog.internal.repository.TreadDesignTextRepository;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
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
 * Applies MKCAT tread-design enrichment from {@code supplier.events.v1} (CAP-324 #1352,
 * ADR-0044 §6, R1).
 *
 * <h2>A separate consumer group from the PRICAT listener, on the same topic</h2>
 *
 * {@link SupplierPriceCatalogEventsListener} already consumes {@code supplier.events.v1} for a
 * different event type. Two independent listeners on one topic each need their own Kafka consumer
 * group, or one would silently steal deliveries meant for the other's filter.
 *
 * <h2>Content-hash staleness, not a version counter</h2>
 *
 * {@code SupplierCatalogUpdatedV1} carries no per-design version — pos-supplier always publishes
 * {@code aggregateVersion=0} — because content has no ordering requirement a stale write could
 * violate the way a price or a quantity would. An unchanged republication (same
 * {@code contentHash}) is a no-op; any changed one is applied, last write wins.
 *
 * <h2>Matching is scoped, never run against the whole catalog</h2>
 *
 * Candidates are the products this exact vendor has actually priced via PRICAT
 * ({@link SupplierPriceEntryRepository#findDistinctProductIdsByVendorProfileId}), scored by
 * {@link TreadDesignMatcher}. A design matching nothing is an ordinary outcome — the row stays,
 * queryable for review, and nothing is treated as an error.
 *
 * <h2>What this never does</h2>
 *
 * Only {@code product.tread_design_id} is written on a product. No dimension, load index, article
 * code or price field is ever touched here — a supplier fact that could redefine a product's
 * identity or structure would hand a vendor edit rights over the catalogue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.catalog.kafka", name = "enabled", havingValue = "true")
public class SupplierCatalogEnrichmentListener {

    /** Producing domain, per the repo-wide processed_events convention. */
    static final String OWNER = "supplier";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final TreadDesignRepository treadDesignRepository;
    private final TreadDesignTextRepository treadDesignTextRepository;
    private final TreadDesignImageRepository treadDesignImageRepository;
    private final SupplierPriceEntryRepository supplierPriceEntryRepository;
    private final ProductRepository productRepository;
    private final TreadDesignMatcher treadDesignMatcher;

    @KafkaListener(
            topics = "${pos.catalog.kafka.supplier-events-topic:supplier.events.v1}",
            groupId =
                    "${pos.catalog.kafka.supplier-catalog-enrichment-consumer-group:pos-catalog-supplier-catalog-enrichment}")
    @Transactional
    public void onSupplierEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable supplier event", e);
            return;
        }
        if (!SupplierCatalogUpdatedV1.EVENT_TYPE.equals(
                envelope.path("eventType").stringValue(null))) {
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping supplier event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            applyUpdate(envelope);
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .owner(OWNER)
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (TransientDataAccessException e) {
            // Rethrown for container retry. Recording this as processed would lose the enrichment
            // with no way to notice: the design would simply never appear.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed supplier catalog event eventId={}", eventId, e);
        }
    }

    private void applyUpdate(JsonNode envelope) {
        SupplierCatalogUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), SupplierCatalogUpdatedV1.class);

        TreadDesignEntity existing = treadDesignRepository
                .findByVendorProfileIdAndVendorVariantId(payload.vendorProfileId(), payload.vendorVariantId())
                .orElse(null);
        if (existing != null && payload.contentHash().equals(existing.getContentHash())) {
            log.debug(
                    "Skipping unchanged tread design vendorProfileId={} vendorVariantId={}",
                    payload.vendorProfileId(),
                    payload.vendorVariantId());
            return;
        }

        TreadDesignEntity design = existing != null ? existing : new TreadDesignEntity();
        design.setVendorProfileId(payload.vendorProfileId());
        design.setSupplierRef(payload.supplierRef());
        design.setVendorVariantId(payload.vendorVariantId());
        design.setBrand(payload.brand());
        design.setTreadDesign(payload.treadDesign());
        design.setTreadDesign2(payload.treadDesign2());
        design.setProductName(payload.productName());
        design.setVehicleType(payload.vehicleType());
        design.setSeasonality(payload.seasonality());
        design.setContentHash(payload.contentHash());
        design.setHasUnresolvedImages(payload.hasUnresolvedImages());
        TreadDesignEntity saved = treadDesignRepository.save(design);

        replaceTexts(saved.getId(), payload.texts());
        replaceImages(saved.getId(), payload.images());
        matchProducts(saved);

        log.debug(
                "Applied tread design vendorProfileId={} vendorVariantId={}",
                payload.vendorProfileId(),
                payload.vendorVariantId());
    }

    /** Wholesale replacement: the event carries the design's full text set on every apply. */
    private void replaceTexts(UUID treadDesignId, List<SupplierCatalogEnrichmentText> texts) {
        treadDesignTextRepository.deleteByTreadDesignId(treadDesignId);
        for (SupplierCatalogEnrichmentText text : texts) {
            treadDesignTextRepository.save(TreadDesignTextEntity.builder()
                    .treadDesignId(treadDesignId)
                    .languageCode(text.languageCode())
                    .name(text.name())
                    .description(text.description())
                    .footNotes(text.footNotes())
                    .build());
        }
    }

    /** Wholesale replacement, same reasoning as {@link #replaceTexts}. */
    private void replaceImages(UUID treadDesignId, List<SupplierCatalogEnrichmentImage> images) {
        treadDesignImageRepository.deleteByTreadDesignId(treadDesignId);
        for (SupplierCatalogEnrichmentImage image : images) {
            treadDesignImageRepository.save(TreadDesignImageEntity.builder()
                    .treadDesignId(treadDesignId)
                    .imageType(image.imageType())
                    .imageId(image.imageId())
                    .contentHash(image.contentHash())
                    .sourceUri(image.sourceUri())
                    .unresolved(image.unresolved())
                    .build());
        }
    }

    /**
     * Attaches this design to every candidate product that matches. Candidates are restricted to
     * products this vendor has actually priced (see class javadoc) — an empty candidate set (a
     * vendor with a marketing feed but no PRICAT prices yet) is not searched further, since nothing
     * in the whole catalog is a scoped candidate.
     */
    private void matchProducts(TreadDesignEntity design) {
        List<UUID> candidateIds =
                supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(design.getVendorProfileId());
        if (candidateIds.isEmpty()) {
            return;
        }
        List<ProductEntity> candidates = productRepository.findAllById(candidateIds);
        for (ProductEntity product : treadDesignMatcher.matchingProducts(design, candidates)) {
            if (!design.getId().equals(product.getTreadDesignId())) {
                product.setTreadDesignId(design.getId());
                productRepository.save(product);
            }
        }
    }
}
