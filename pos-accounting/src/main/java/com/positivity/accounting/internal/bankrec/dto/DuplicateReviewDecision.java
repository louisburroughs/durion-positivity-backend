package com.positivity.accounting.internal.bankrec.dto;

/** A human's decision on a {@code POSSIBLE_DUPLICATE} bank transaction (SPEC §4.5; story S2, #2301). */
public enum DuplicateReviewDecision {
    /** Not a duplicate: the row becomes {@code UNMATCHED}. */
    DISTINCT,
    /** A duplicate: the row becomes {@code EXCLUDED}, pointing at its original. */
    DUPLICATE
}
