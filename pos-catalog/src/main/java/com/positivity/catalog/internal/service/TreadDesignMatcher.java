package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Scores a tread design against catalog products by character-trigram overlap (CAP-324 #1352).
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
 * set is deliberately the caller's.
 */
@Component
public class TreadDesignMatcher {

    /**
     * Minimum trigram-overlap ratio to accept a match. High enough that two unrelated short brand
     * names cannot cross it by chance, low enough that spacing/punctuation drift between
     * independently authored text does not defeat a real match.
     */
    static final double MATCH_THRESHOLD = 0.5;

    /** Trigram count; short enough to tolerate a one-word difference, long enough to avoid noise. */
    private static final int NGRAM_SIZE = 3;

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

    /** Whether {@code product} scores at or above {@link #MATCH_THRESHOLD} for {@code design}. */
    public boolean matches(@NonNull TreadDesignEntity design, @NonNull ProductEntity product) {
        return score(design, product) >= MATCH_THRESHOLD;
    }

    /** Every candidate scoring at or above threshold; a design may legitimately match several sizes. */
    @NonNull
    public List<ProductEntity> matchingProducts(
            @NonNull TreadDesignEntity design, @NonNull List<ProductEntity> candidates) {
        return candidates.stream()
                .filter(candidate -> matches(design, candidate))
                .toList();
    }

    private static Set<String> trigrams(String... texts) {
        StringBuilder normalized = new StringBuilder();
        for (String text : texts) {
            if (text != null) {
                normalized.append(text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
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
