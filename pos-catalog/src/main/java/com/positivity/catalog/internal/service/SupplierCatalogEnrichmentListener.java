package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import com.positivity.catalog.internal.entity.TreadDesignMatchCandidateEntity;
import com.positivity.catalog.internal.entity.TreadDesignTextEntity;
import com.positivity.catalog.internal.enums.MatchTier;
import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import com.positivity.catalog.internal.enums.TreadDesignSource;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.SupplierPriceEntryRepository;
import com.positivity.catalog.internal.repository.TreadDesignImageRepository;
import com.positivity.catalog.internal.repository.TreadDesignMatchCandidateRepository;
import com.positivity.catalog.internal.repository.TreadDesignRepository;
import com.positivity.catalog.internal.repository.TreadDesignTextRepository;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * <h2>Confidence, not a single threshold (#1645)</h2>
 *
 * The matcher now returns a tier per candidate. Only unambiguous AUTO-tier candidates are attached;
 * REVIEW-tier ones are recorded and the design is parked in {@code REVIEW} for a person. Two designs
 * claiming one product at AUTO tier park both and attach neither — under #1352 the later event
 * simply won, which meant a product's enrichment could change because of an unrelated vendor's
 * publication and nothing recorded that it had. A product a reviewer attached by hand
 * ({@code tread_design_source = MANUAL}) is never re-pointed here at all.
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

    /**
     * How many scored candidates are kept per design. A reviewer compares a handful of plausible
     * products; a vendor with ten thousand priced SKUs would otherwise write a candidate table
     * nobody can page through to answer the one question it exists for.
     */
    static final int MAX_STORED_CANDIDATES = 20;

    /** Matches {@code numeric(5,4)} in V20 — the stored score must equal the compared score. */
    private static final int SCORE_SCALE = 4;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final TreadDesignRepository treadDesignRepository;
    private final TreadDesignTextRepository treadDesignTextRepository;
    private final TreadDesignImageRepository treadDesignImageRepository;
    private final TreadDesignMatchCandidateRepository treadDesignMatchCandidateRepository;
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
        if (design.getMatchState() == null) {
            // A design that has only just arrived has not matched anything yet, which is a state
            // rather than the absence of one — matchProducts below replaces it with the outcome.
            design.setMatchState(TreadDesignMatchState.UNMATCHED);
            design.setMatchStateAt(Instant.now(clock));
        }
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
        if (shouldRematch(saved)) {
            matchProducts(saved);
        }

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
     * Whether an automatic pass may touch this design's attachments (#1645).
     *
     * <p>Everything re-enters matching when the vendor changes what it published — including a
     * design a reviewer REJECTED, because the rejection was of the words the vendor used and the
     * vendor has now used different ones. The single exception is a design a person has already
     * attached by hand: re-running the matcher over it would either confirm what the reviewer
     * already decided or contradict it silently, and neither is worth doing.
     */
    private boolean shouldRematch(TreadDesignEntity design) {
        if (design.getMatchState() != TreadDesignMatchState.MATCHED) {
            return true;
        }
        return !productRepository.existsByTreadDesignIdAndTreadDesignSource(design.getId(), TreadDesignSource.MANUAL);
    }

    /**
     * Scores this design against the products its vendor has priced, records what it saw, and
     * attaches only what it is entitled to attach (#1645).
     *
     * <p>Candidates are restricted to products this exact vendor has actually priced (see class
     * javadoc) — an empty candidate set (a vendor with a marketing feed but no PRICAT prices yet)
     * leaves the design UNMATCHED, which is an ordinary outcome and not an error.
     *
     * <p>Three rules decide what happens to an AUTO-tier candidate, and all three exist because
     * #1352 had none of them: a product a person attached by hand is never re-pointed; a product
     * two designs both claim at AUTO tier is attached to neither, because picking one would make an
     * arbitrary choice permanent and invisible; and an AUTO attachment this design made earlier
     * that no longer scores is cleared, because leaving it would let a stale guess outlive the text
     * that justified it.
     */
    private void matchProducts(TreadDesignEntity design) {
        List<UUID> candidateIds =
                supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(design.getVendorProfileId());
        List<ProductEntity> candidates =
                candidateIds.isEmpty() ? List.of() : productRepository.findAllById(candidateIds);
        List<TreadDesignMatcher.ScoredCandidate> scored = treadDesignMatcher.evaluateCandidates(design, candidates);

        recordCandidates(design, scored);

        List<ProductEntity> attachable = new ArrayList<>();
        for (TreadDesignMatcher.ScoredCandidate candidate : scored) {
            if (candidate.tier() != MatchTier.AUTO) {
                continue;
            }
            ProductEntity product = candidate.product();
            if (TreadDesignSource.MANUAL == product.getTreadDesignSource()) {
                log.debug(
                        "Leaving manually attached product productId={} alone for designId={}",
                        product.getId(),
                        design.getId());
                continue;
            }
            if (parkAmbiguousClaim(design, product)) {
                continue;
            }
            attachable.add(product);
        }

        clearStaleAutoAttachments(design, attachable);
        for (ProductEntity product : attachable) {
            product.setTreadDesignId(design.getId());
            product.setTreadDesignSource(TreadDesignSource.AUTO);
            productRepository.save(product);
        }

        if (!attachable.isEmpty()) {
            setState(design, TreadDesignMatchState.MATCHED);
        } else if (!scored.isEmpty()) {
            // Something resembled this design but nothing was attachable — the case a person has to
            // look at, and the case #1352 could not express at all.
            setState(design, TreadDesignMatchState.REVIEW);
        } else {
            setState(design, TreadDesignMatchState.UNMATCHED);
        }
    }

    /** Replaces this design's candidate rows with the current scoring, best first and bounded. */
    private void recordCandidates(TreadDesignEntity design, List<TreadDesignMatcher.ScoredCandidate> scored) {
        treadDesignMatchCandidateRepository.deleteByTreadDesignId(design.getId());
        scored.stream()
                .limit(MAX_STORED_CANDIDATES)
                .forEach(candidate -> treadDesignMatchCandidateRepository.save(TreadDesignMatchCandidateEntity.builder()
                        .treadDesignId(design.getId())
                        .productId(candidate.product().getId())
                        .score(BigDecimal.valueOf(candidate.score()).setScale(SCORE_SCALE, RoundingMode.HALF_UP))
                        .tier(candidate.tier())
                        .build()));
    }

    /**
     * Parks both designs when another design also claims this product at AUTO tier, and reports
     * whether it did.
     *
     * <p>The other design is moved to REVIEW as well, and an AUTO attachment it already holds on
     * this product is cleared: the moment a second claimant appears, the first claim stopped being
     * a confident answer, and continuing to display it as one is the failure this rule exists to
     * prevent. A MANUAL attachment is not touched here — it never reached this method.
     */
    private boolean parkAmbiguousClaim(TreadDesignEntity design, ProductEntity product) {
        List<TreadDesignMatchCandidateEntity> rivals =
                treadDesignMatchCandidateRepository.findByProductIdAndTierAndTreadDesignIdNot(
                        product.getId(), MatchTier.AUTO, design.getId());
        if (rivals.isEmpty()) {
            return false;
        }
        log.info(
                "Parking ambiguous tread-design claim productId={} designId={} rivals={}",
                product.getId(),
                design.getId(),
                rivals.size());
        for (TreadDesignMatchCandidateEntity rival : rivals) {
            treadDesignRepository.findById(rival.getTreadDesignId()).ifPresent(rivalDesign -> {
                if (rivalDesign.getMatchState() != TreadDesignMatchState.REVIEW) {
                    setState(rivalDesign, TreadDesignMatchState.REVIEW);
                }
            });
        }
        if (product.getTreadDesignId() != null
                && TreadDesignSource.AUTO == product.getTreadDesignSource()
                && rivals.stream().anyMatch(rival -> rival.getTreadDesignId().equals(product.getTreadDesignId()))) {
            product.setTreadDesignId(null);
            product.setTreadDesignSource(null);
            productRepository.save(product);
        }
        return true;
    }

    /**
     * Detaches products this design attached automatically that no longer score at AUTO tier.
     * MANUAL attachments are excluded by their source, not by an accident of ordering.
     */
    private void clearStaleAutoAttachments(TreadDesignEntity design, List<ProductEntity> keeping) {
        Set<UUID> keepIds = keeping.stream().map(ProductEntity::getId).collect(Collectors.toSet());
        for (ProductEntity attached : productRepository.findByTreadDesignId(design.getId())) {
            if (TreadDesignSource.AUTO == attached.getTreadDesignSource() && !keepIds.contains(attached.getId())) {
                attached.setTreadDesignId(null);
                attached.setTreadDesignSource(null);
                productRepository.save(attached);
            }
        }
    }

    private void setState(TreadDesignEntity design, TreadDesignMatchState state) {
        design.setMatchState(state);
        design.setMatchStateAt(Instant.now(clock));
        treadDesignRepository.save(design);
    }
}
