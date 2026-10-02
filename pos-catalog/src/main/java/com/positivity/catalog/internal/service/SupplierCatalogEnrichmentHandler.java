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
import com.positivity.domainevents.supplier.SupplierCatalogRepublishCompletedV1;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies MKCAT tread-design enrichment from {@code supplier.events.v1} (CAP-324 #1352,
 * ADR-0044 §6, R1).
 *
 * <p>This class is not a Kafka consumer: {@link SupplierEventsListener} is the single consumer of
 * {@code supplier.events.v1} and routes {@code supplier.catalog.updated} and
 * {@code supplier.catalog.republish.completed} here after its {@code processed_events} guard (#2177).
 *
 * <h2>Content-hash staleness, not a version counter</h2>
 *
 * {@code SupplierCatalogUpdatedV1} carries no per-design version — pos-supplier always publishes
 * {@code aggregateVersion=0} — because content has no ordering requirement a stale write could
 * violate the way a price or a quantity would. An unchanged republication (same
 * {@code contentHash}) is a no-op; any changed one is applied, last write wins.
 *
 * <p>That no-op is what makes the owner's re-emit-all safe (#2356). pos-supplier answers
 * {@code supplier.catalog.republish.requested} by publishing every variant it has staged for a
 * vendor profile again, under new event ids the {@code processed_events} guard has never seen. A
 * design this module already holds arrives with the hash it already has and is left exactly as it
 * is — no re-match, no candidate rows replaced, no review decision or worklist position disturbed —
 * and only a design that was lost, or whose newer content was, is applied.
 *
 * <h2>Seeing a gap, since nothing else shows one (#2356)</h2>
 *
 * A lost enrichment leaves no trace: a design that never arrived looks exactly like one the vendor
 * never published. The re-publication's closing event carries how many variants the owner holds for
 * the profile, and {@link #reportRepublishGap} compares that against the designs held here, as a
 * WARN and as the {@value #DESIGN_GAP_METRIC} gauge. It reports and does nothing else: unlike a
 * short PRICAT import, a shortfall here does not ask the owner to re-emit again, because the
 * re-emit that just finished <em>was</em> the remedy and asking again from inside its own completion
 * would loop.
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
 *
 * <h2>Transaction shape (#2146)</h2>
 *
 * The handler method is not {@code @Transactional}: the apply and its {@code processed_events}
 * mark run together in a transaction of their own ({@code REQUIRES_NEW}), so a permanent failure
 * rolls back only this event's work instead of poisoning a shared transaction whose commit the
 * container would retry to the DLQ. Unlike the PRICAT handler the mark stays inside that
 * transaction — a failed apply has never recorded its eventId here — so there is no window between
 * two commits. Transient failures still propagate for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.catalog.kafka", name = "enabled", havingValue = "true")
public class SupplierCatalogEnrichmentHandler {

    /** Producing domain, per the repo-wide processed_events convention. */
    static final String OWNER = "supplier";

    /**
     * How many {@code REVIEW}-tier candidates are kept per design, best score first.
     *
     * <p>{@code AUTO}-tier candidates are never capped, unlike {@code REVIEW}-tier ones: they are
     * exactly the rows {@link #matchProducts}'s attach loop and {@link #parkAmbiguousClaim}'s rival
     * lookup depend on for correctness, not just for a reviewer's convenience. Capping them would
     * mean an attachment beyond the cap has no candidate row to explain it, and a design with more
     * AUTO-tier candidates than the cap could let a later rival attach uncontested because the
     * ambiguity check can only see what got persisted. {@code REVIEW}-tier rows carry no such
     * obligation — nothing acts on them automatically, a person only ever looks at them — so they
     * stay capped for the reason #1352 never intended a reviewer to page through a vendor's entire
     * priced catalogue to find the handful worth a decision.
     */
    static final int MAX_STORED_REVIEW_CANDIDATES = 20;

    /**
     * Gauge of tread designs the owner re-emitted for a vendor profile that this module does not
     * hold, as of that profile's last re-publication. Tagged {@code vendorProfileId}.
     */
    static final String DESIGN_GAP_METRIC = "catalog.enrichment.design.gap";

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

    @Nullable
    private final MeterRegistry meterRegistry;

    /** The value behind each vendor profile's {@value #DESIGN_GAP_METRIC} gauge. */
    private final Map<UUID, AtomicLong> designGaps = new ConcurrentHashMap<>();

    /** The apply and its processed mark, in one transaction of their own; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public SupplierCatalogEnrichmentHandler(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            TreadDesignRepository treadDesignRepository,
            TreadDesignTextRepository treadDesignTextRepository,
            TreadDesignImageRepository treadDesignImageRepository,
            TreadDesignMatchCandidateRepository treadDesignMatchCandidateRepository,
            SupplierPriceEntryRepository supplierPriceEntryRepository,
            ProductRepository productRepository,
            TreadDesignMatcher treadDesignMatcher,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.treadDesignRepository = treadDesignRepository;
        this.treadDesignTextRepository = treadDesignTextRepository;
        this.treadDesignImageRepository = treadDesignImageRepository;
        this.treadDesignMatchCandidateRepository = treadDesignMatchCandidateRepository;
        this.supplierPriceEntryRepository = supplierPriceEntryRepository;
        this.productRepository = productRepository;
        this.treadDesignMatcher = treadDesignMatcher;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Applies one {@code supplier.catalog.updated} or {@code supplier.catalog.republish.completed}
     * event and marks it processed in the same transaction.
     *
     * @param envelope the parsed event envelope, already de-duplicated by the dispatcher
     * @param eventId the envelope's non-blank {@code eventId}
     */
    public void handle(@NonNull JsonNode envelope, @NonNull String eventId) {
        String eventType = envelope.path("eventType").stringValue(null);
        try {
            handlerTransaction.executeWithoutResult(_ -> {
                if (SupplierCatalogRepublishCompletedV1.EVENT_TYPE.equals(eventType)) {
                    reportRepublishGap(envelope);
                } else {
                    applyUpdate(envelope);
                }
                processedEventRepository.save(ProcessedEvent.builder()
                        .eventId(eventId)
                        .owner(OWNER)
                        .processedAt(Instant.now(clock))
                        .build());
            });
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

    /**
     * Compares the variants the owner just re-emitted for a vendor profile against the designs held
     * for it here, and reports the difference (#2356).
     *
     * <p>Every {@code supplier.catalog.updated} creates or updates exactly one design keyed on
     * {@code (vendorProfileId, vendorVariantId)} and nothing on this path deletes one, so after a
     * re-publication has been applied this module holds at least as many designs as the owner
     * re-emitted. Holding fewer means enrichments are missing.
     *
     * <p>The count is taken when this event is handled, and the events it counts are keyed per
     * variant while this one is keyed per vendor profile. On a topic with more than one partition
     * it can therefore be handled while re-emitted variants are still in flight, and a shortfall
     * reported here can close by itself. The gauge holds the value until the profile's next
     * re-publication restates it; a gap that survives a second one is real.
     */
    private void reportRepublishGap(@NonNull JsonNode envelope) {
        SupplierCatalogRepublishCompletedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), SupplierCatalogRepublishCompletedV1.class);

        long held = treadDesignRepository.countByVendorProfileId(payload.vendorProfileId());
        long missing = Math.max(0, payload.variantCount() - held);
        recordGap(payload.vendorProfileId(), missing);

        if (missing > 0) {
            log.warn(
                    "MKCAT re-publication for vendorProfileId={} ({}) re-emitted {} designs but {} are held:"
                            + " {} missing. Expected only while re-emitted events are still being applied;"
                            + " if it persists after another re-publication, enrichments are being lost",
                    payload.vendorProfileId(),
                    payload.supplierRef(),
                    payload.variantCount(),
                    held,
                    missing);
        } else {
            log.info(
                    "MKCAT re-publication for vendorProfileId={} ({}) complete: {} designs re-emitted, {} held",
                    payload.vendorProfileId(),
                    payload.supplierRef(),
                    payload.variantCount(),
                    held);
        }
    }

    /** Sets the vendor profile's gap gauge, registering it the first time the profile is seen. */
    private void recordGap(@NonNull UUID vendorProfileId, long missing) {
        designGaps
                .computeIfAbsent(vendorProfileId, id -> {
                    AtomicLong value = new AtomicLong();
                    if (meterRegistry != null) {
                        Gauge.builder(DESIGN_GAP_METRIC, value, AtomicLong::get)
                                .description("Tread designs the supplier re-emitted for a vendor profile that"
                                        + " pos-catalog does not hold, as of the last MKCAT re-publication")
                                .tag("vendorProfileId", id.toString())
                                .register(meterRegistry);
                    }
                    return value;
                })
                .set(missing);
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

    /**
     * Replaces this design's candidate rows with the current scoring: every {@code AUTO}-tier
     * candidate, plus the best-scoring {@link #MAX_STORED_REVIEW_CANDIDATES} {@code REVIEW}-tier
     * ones. {@code scored} arrives best-score-first (see {@link TreadDesignMatcher#evaluateCandidates})
     * and {@code AUTO} always outscores {@code REVIEW} under the configured thresholds, so a single
     * pass in that order caps only the {@code REVIEW} tail without needing to partition first.
     *
     * <p>This is deliberately the exact set {@link #matchProducts}'s attach loop iterates over for
     * {@code AUTO} candidates — persisting fewer would silently break both traceability (an
     * attachment with no candidate row) and {@link #parkAmbiguousClaim}'s rival lookup, which only
     * ever sees rows that made it to this table.
     */
    private void recordCandidates(TreadDesignEntity design, List<TreadDesignMatcher.ScoredCandidate> scored) {
        treadDesignMatchCandidateRepository.deleteByTreadDesignId(design.getId());
        int reviewKept = 0;
        for (TreadDesignMatcher.ScoredCandidate candidate : scored) {
            if (candidate.tier() == MatchTier.REVIEW) {
                if (reviewKept >= MAX_STORED_REVIEW_CANDIDATES) {
                    continue;
                }
                reviewKept++;
            }
            treadDesignMatchCandidateRepository.save(TreadDesignMatchCandidateEntity.builder()
                    .treadDesignId(design.getId())
                    .productId(candidate.product().getId())
                    .score(BigDecimal.valueOf(candidate.score()).setScale(SCORE_SCALE, RoundingMode.HALF_UP))
                    .tier(candidate.tier())
                    .build());
        }
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

    /**
     * Sets the design's match state, ageing {@code matchStateAt} only when the state actually
     * moves. The review worklist orders on {@code matchStateAt} (see {@link
     * com.positivity.catalog.internal.repository.TreadDesignRepository#findForReview}) precisely so
     * it ages on decisions changing, not on every vendor re-publication or re-match that happens to
     * land the design back on the state it already had.
     */
    private void setState(TreadDesignEntity design, TreadDesignMatchState state) {
        if (design.getMatchState() != state) {
            design.setMatchStateAt(Instant.now(clock));
        }
        design.setMatchState(state);
        treadDesignRepository.save(design);
    }
}
