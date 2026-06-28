package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.MatchConfidence;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Result of bill-to-invoice matching operation.
 * Contains best match, confidence level, and alternative candidates.
 */
@Value
@Builder
@Schema(description = "Result of a bill-to-invoice matching operation")
public class BillMatchResult {

    /** Best matching bill (null if NO_MATCH) */
    @Nullable
    @Schema(description = "Best matching vendor bill (null if no match was found)", requiredMode = NOT_REQUIRED)
    VendorBill bestMatch;

    /** Confidence level of the match */
    @Schema(description = "Confidence level of the match", example = "HIGH_CONFIDENCE", requiredMode = REQUIRED)
    MatchConfidence confidence;

    /** Score of the best match (0 if no match) */
    @Schema(description = "Score of the best match (0 if no match)", example = "87", requiredMode = REQUIRED)
    int bestScore;

    /** Alternative candidates (populated when AMBIGUOUS) */
    @Nullable
    @Schema(
            description = "Alternative candidate bills (populated when the match is ambiguous)",
            requiredMode = NOT_REQUIRED)
    List<ScoredBill> alternativeCandidates;

    /** Detailed matching audit trail */
    @Schema(
            description = "Detailed matching audit trail",
            example = "Matched on amount and vendor; date within tolerance",
            requiredMode = REQUIRED)
    String matchingDetails;

    /**
     * Vendor bill with its match score and breakdown.
     */
    @Value
    @Builder
    @Schema(description = "A vendor bill with its match score and score breakdown")
    public static class ScoredBill {

        @Schema(description = "The scored vendor bill", requiredMode = REQUIRED)
        VendorBill bill;

        @Schema(description = "Total match score for the bill", example = "87", requiredMode = REQUIRED)
        int totalScore;

        @Schema(
                description = "Human-readable breakdown of how the score was computed",
                example = "amount=40, vendor=30, date=17",
                requiredMode = REQUIRED)
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
