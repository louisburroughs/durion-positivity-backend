package com.positivity.accounting.internal.bankrec.enums;

/** Why a line is a match candidate, and what it scored (SPEC §3.4 {@code reasons[]}, §4.6; story S4, #2303). */
public enum CandidateReason {
    /** Exact signed amount: +60. */
    EXACT_AMOUNT,
    /** Amount within ±0.01: +40. */
    WITHIN_TOLERANCE,
    /** Dated within W days: +20 × (1 − d / W). */
    DATE_IN_WINDOW,
    /** Dated beyond W days — a candidate only because the caller widened the window: +0. */
    DATE_OUT_OF_WINDOW,
    /** The bank reference or check number is a token of the ledger description: +20. */
    REFERENCE_MATCH,
    /** Description tokens overlap (Jaccard ≥ 0.5): up to +10. */
    DESCRIPTION_SIMILAR
}
