package com.positivity.accounting.internal.bankrec.enums;

/**
 * Why a match needs a human justification before it is accepted (SPEC §3.4 M5; story S4, #2303). A
 * {@code MATCH_REQUIRES_REVIEW} refusal lists these in {@code fieldErrors[justification]}.
 */
public enum MatchReviewReason {
    /** More than one member on a side (ONE_TO_MANY or MANY_TO_ONE). */
    CARDINALITY_NOT_ONE_TO_ONE,
    /** The sides differ by a residual within ±0.01 ({@code toleranceUsed ≠ 0}). */
    TOLERANCE_USED,
    /** A member dated before the window, or a bank and a ledger member further apart than W. */
    DATE_OUT_OF_WINDOW,
    /** A bank member was a {@code POSSIBLE_DUPLICATE} reviewed distinct. */
    FORMER_POSSIBLE_DUPLICATE
}
