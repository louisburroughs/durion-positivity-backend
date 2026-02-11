package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.MatchConfidence;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Result of bill-to-invoice matching operation.
 * Contains best match, confidence level, and alternative candidates.
 */
@Value
@Builder
public class BillMatchResult {

    /** Best matching bill (null if NO_MATCH) */
    @Nullable
    VendorBill bestMatch;

    /** Confidence level of the match */
    MatchConfidence confidence;

    /** Score of the best match (0 if no match) */
    int bestScore;

    /** Alternative candidates (populated when AMBIGUOUS) */
    @Nullable
    List<ScoredBill> alternativeCandidates;

    /** Detailed matching audit trail */
    String matchingDetails;

    /**
     * Vendor bill with its match score and breakdown.
     */
    @Value
    @Builder
    public static class ScoredBill {
        VendorBill bill;
        int totalScore;
        String scoreBreakdown;
    }

    /**
     * Create a high confidence match result.
     */
    public static BillMatchResult highConfidence(VendorBill bill, int score, String details) {
        return BillMatchResult.builder()
                .bestMatch(bill)
                .confidence(MatchConfidence.HIGH_CONFIDENCE)
                .bestScore(score)
                .matchingDetails(details)
                .build();
    }

    /**
     * Create a medium confidence match result.
     */
    public static BillMatchResult mediumConfidence(VendorBill bill, int score, String details) {
        return BillMatchResult.builder()
                .bestMatch(bill)
                .confidence(MatchConfidence.MEDIUM_CONFIDENCE)
                .bestScore(score)
                .matchingDetails(details)
                .build();
    }

    /**
     * Create an ambiguous match result with multiple candidates.
     */
    public static BillMatchResult ambiguous(List<ScoredBill> candidates, String details) {
        return BillMatchResult.builder()
                .bestMatch(candidates.isEmpty() ? null : candidates.get(0).getBill())
                .confidence(MatchConfidence.AMBIGUOUS)
                .bestScore(candidates.isEmpty() ? 0 : candidates.get(0).getTotalScore())
                .alternativeCandidates(candidates)
                .matchingDetails(details)
                .build();
    }

    /**
     * Create a no-match result.
     */
    public static BillMatchResult noMatch(String details) {
        return BillMatchResult.builder()
                .bestMatch(null)
                .confidence(MatchConfidence.NO_MATCH)
                .bestScore(0)
                .matchingDetails(details)
                .build();
    }
}
