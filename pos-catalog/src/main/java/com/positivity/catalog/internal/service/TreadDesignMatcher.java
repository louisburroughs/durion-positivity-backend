package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogEnrichmentProperties;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.enums.MatchTier;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Scores a tread design against catalog products and places each result in a confidence tier
 * (CAP-324 #1352, tiers added by #1645).
 *
 * <h2>Two gates, not one number</h2>
 *
 * A candidate must clear the brand gate <em>and</em> a text threshold. The brand gate
 * ({@link BrandNormalizer}) is decisive: brands that disagree after normalisation and alias
 * resolution end the comparison at {@link MatchTier#NONE} whatever the text says, because no degree
 * of design-name resemblance makes a Michelin design describe a Continental tyre. Only then does the
 * trigram score decide between attaching without asking ({@code AUTO}), showing a person
 * ({@code REVIEW}) and ignoring ({@code NONE}).
 *
 * <p>#1352 had a single 0.50 threshold and attached everything above it, so a plausible-but-uncertain
 * pairing and a near-certain one were treated identically — and the uncertain ones were exactly the
 * pairings nobody could later find, because nothing recorded that a decision had been made. The
 * thresholds are configuration ({@link CatalogEnrichmentProperties}); 0.50 survives as the review
 * floor, so nothing that was considered under #1352 stops being considered.
 *
 * <h2>Why trigrams rather than word tokens</h2>
 *
 * MKCAT's {@code brand}/{@code treadDesign}/{@code treadDesign2}/{@code productName} and a product's
 * own {@code manufacturerBrand}/{@code name} are independently authored free text, and the gap
 * between them is often a single space or hyphen rather than a different word — "Pilot Sport 4S" on
 * one side, "Pilot Sport 4 S" on the other. Word-token Jaccard treats that as two different tokens
 * ("4s" vs. "4" and "s") and loses most of the match; character trigrams over the whole
 * whitespace-stripped string are close to unaffected by exactly this kind of spacing/punctuation
 * drift, because removing one space changes only the handful of trigrams that crossed it.
 *
 * <h2>Why this alone is not enough, and is never run unscoped</h2>
 *
 * Free-text similarity against the whole catalog would produce both misses (real matches scored just
 * under threshold) and false hits (an unrelated product with coincidentally similar characters).
 * Callers must restrict {@code candidates} to products a real relationship already exists with — in
 * practice, products the design's own vendor has priced via PRICAT
 * ({@code SupplierPriceEntryRepository}). Scoring is this class's whole job; choosing the candidate
 * set is deliberately the caller's, and deciding what to do with a tier is the listener's.
 */
@Component
@RequiredArgsConstructor
public class TreadDesignMatcher {

    /** Trigram count; short enough to tolerate a one-word difference, long enough to avoid noise. */
    private static final int NGRAM_SIZE = 3;

    private final CatalogEnrichmentProperties properties;
    private final BrandNormalizer brandNormalizer;

    /**
     * One product's standing against one design.
     *
     * @param product the candidate product
     * @param score trigram overlap in {@code [0.0, 1.0]}; 0.0 when the brand gate rejected the pair
     * @param tier what that score means under the configured thresholds
     */
    public record ScoredCandidate(
            @NonNull ProductEntity product,
            double score,
            @NonNull MatchTier tier) {}

    /** The configured score at or above which an unambiguous candidate is attached without asking. */
    public double autoThreshold() {
        return properties.autoThresholdOrDefault();
    }

    /** The configured score at or above which a candidate is worth showing a reviewer. */
    public double reviewThreshold() {
        return properties.reviewThresholdOrDefault();
    }

    /** Trigram-overlap score in {@code [0.0, 1.0]}; 0.0 when either side has no usable text. */
    public double score(@NonNull TreadDesignEntity design, @NonNull ProductEntity product) {
        Set<String> designGrams =
                trigrams(design.getBrand(), design.getTreadDesign(), design.getTreadDesign2(), design.getProductName());
        Set<String> productGrams = trigrams(product.getManufacturerBrand(), product.getName());
        if (designGrams.isEmpty() || productGrams.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(designGrams);
        intersection.retainAll(productGrams);
        Set<String> union = new HashSet<>(designGrams);
        union.addAll(productGrams);
        return (double) intersection.size() / union.size();
    }

    /**
     * Brand gate first, then thresholds. A brand disagreement is {@link MatchTier#NONE} at score
     * 0.0 — reported as zero rather than as the text score it would have had, so that a stored
     * candidate row can never be read as "this nearly matched" when the gate is what stopped it.
     */
    @NonNull
    public ScoredCandidate evaluate(@NonNull TreadDesignEntity design, @NonNull ProductEntity product) {
        if (!brandNormalizer.sameBrand(design.getBrand(), product.getManufacturerBrand())) {
            return new ScoredCandidate(product, 0.0, MatchTier.NONE);
        }
        double score = score(design, product);
        return new ScoredCandidate(product, score, tierFor(score));
    }

    /** The tier a bare score falls in, brand gate assumed already passed. */
    @NonNull
    public MatchTier tierFor(double score) {
        if (score >= autoThreshold()) {
            return MatchTier.AUTO;
        }
        if (score >= reviewThreshold()) {
            return MatchTier.REVIEW;
        }
        return MatchTier.NONE;
    }

    /**
     * Every candidate worth recording — {@code AUTO} and {@code REVIEW} tiers — best score first.
     *
     * <p>{@code NONE} results are dropped rather than stored: they are the overwhelming majority of
     * any vendor's priced catalogue, and a review table that holds one row per product a design is
     * not related to is a table nobody can read.
     */
    @NonNull
    public List<ScoredCandidate> evaluateCandidates(
            @NonNull TreadDesignEntity design, @NonNull List<ProductEntity> candidates) {
        return candidates.stream()
                .map(candidate -> evaluate(design, candidate))
                .filter(scored -> scored.tier() != MatchTier.NONE)
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
                .toList();
    }

    private static Set<String> trigrams(String... texts) {
        // A manual character filter rather than a regex: score() runs once per candidate per
        // design, so a vendor with many priced products means this recompiling and re-matching a
        // pattern on every call — a plain loop over already-known ASCII ranges avoids both the
        // regex engine and the intermediate strings replaceAll would allocate.
        StringBuilder normalized = new StringBuilder();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (int i = 0; i < text.length(); i++) {
                char c = Character.toLowerCase(text.charAt(i));
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    normalized.append(c);
                }
            }
        }
        String text = normalized.toString();
        if (text.isEmpty()) {
            return Set.of();
        }
        if (text.length() < NGRAM_SIZE) {
            return Set.of(text);
        }
        Set<String> grams = new HashSet<>();
        for (int i = 0; i <= text.length() - NGRAM_SIZE; i++) {
            grams.add(text.substring(i, i + NGRAM_SIZE));
        }
        return grams;
    }
}
