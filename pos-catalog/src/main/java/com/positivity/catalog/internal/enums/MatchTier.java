package com.positivity.catalog.internal.enums;

/**
 * How much confidence the matcher has in one (design, product) pair (#1645).
 *
 * <p>The tier, not the raw score, is what the rest of the system reasons about: the thresholds that
 * produce it are configuration ({@code pos.catalog.enrichment.auto-threshold} and
 * {@code pos.catalog.enrichment.review-threshold}), and a deployment that tunes them must not have
 * to re-teach every caller what a particular number means.
 */
public enum MatchTier {

    /** At or above the auto threshold: attachable without asking, if nothing else claims it. */
    AUTO,

    /** Between the review floor and the auto threshold: shown to a person, never attached alone. */
    REVIEW,

    /** Below the review floor, or the brand gate rejected the pair. Not a candidate at all. */
    NONE
}
