package com.positivity.accounting.internal.enums;

/**
 * Confidence level for bill-to-invoice matching.
 * Used to determine approval workflow.
 */
public enum MatchConfidence {
    /**
     * Single candidate with score > 70 points.
     * Auto-approve without human review.
     */
    HIGH_CONFIDENCE,

    /**
     * Single candidate with score 50-70 points.
     * Require human review before approval.
     */
    MEDIUM_CONFIDENCE,

    /**
     * Multiple candidates with score > 50 points.
     * Create approval task with all candidates for manual selection.
     */
    AMBIGUOUS,

    /**
     * No candidates with score > 50 points.
     * Create exception for procurement team.
     */
    NO_MATCH
}
