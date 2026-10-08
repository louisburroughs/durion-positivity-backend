package com.positivity.accounting.internal.enums;

/**
 * Confidence level for bill-to-invoice matching.
 * Used to determine approval workflow.
 */
public enum MatchConfidence {
    /**
     * Single candidate with a score of 70 points or more. Sent for approval ({@code AWAITING_APPROVAL}, submitter
     * {@code SYSTEM}); approved by the system only within the AP approval policy's automatic limit (#2510).
     */
    HIGH_CONFIDENCE,

    /**
     * Single candidate with a score from 50 to 69 points. {@code MATCH_EXCEPTION}: a person reviews it.
     */
    MEDIUM_CONFIDENCE,

    /**
     * Several candidates with 50 points or more. The candidates are kept for a person to select one.
     */
    AMBIGUOUS,

    /**
     * No candidate with 50 points or more. Refused: no pending receipt matches the invoice.
     */
    NO_MATCH
}
