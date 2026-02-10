package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Internal DTO to hold mapping evaluation results.
 * Used by PostingRuleEvaluator to track the outcome of mapping key evaluation.
 * 
 * This tracks:
 * - Whether a mapping was successfully found
 * - The type of mapping matched (exact, fallback, category_default)
 * - The mapping key that was used
 * - All keys that were evaluated during the search
 * - Resolved GL account lines for journal entry generation
 */
@Data
@NoArgsConstructor
public class MappingEvaluation {
    /**
     * Whether a mapping was successfully found.
     */
    private boolean success;

    /**
     * Type of mapping that was matched: "exact", "fallback", or "category_default".
     */
    private String mappingType;

    /**
     * The mapping key that was successfully matched.
     */
    private String mappingKey;

    /**
     * List of all keys that were evaluated during the search.
     * Useful for debugging and audit trails.
     */
    private List<String> keysEvaluated = new ArrayList<>();

    /**
     * Resolved journal entry line definitions from mapping evaluation.
     * Each line specifies a resolved GL account and its debit/credit amount.
     */
    private List<ResolvedLine> resolvedLines = new ArrayList<>();

    /**
     * One resolved journal entry line with GL account and amount.
     */
    @Data
    @NoArgsConstructor
    public static class ResolvedLine {
        private UUID glAccountId;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
        private String description;

        public ResolvedLine(UUID glAccountId, BigDecimal debitAmount, BigDecimal creditAmount, String description) {
            this.glAccountId = glAccountId;
            this.debitAmount = debitAmount != null ? debitAmount : BigDecimal.ZERO;
            this.creditAmount = creditAmount != null ? creditAmount : BigDecimal.ZERO;
            this.description = description;
        }
    }
}
