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

    /**
     * Add a single key to the evaluated keys list.
     * <p>
     * This is the preferred method for adding keys. Do NOT call
     * {@code getKeysEvaluated().add(...)},
     * as the getter returns an immutable list that will throw
     * {@code UnsupportedOperationException}.
     * 
     * @param key the evaluated key to add
     */
    public void addEvaluatedKey(String key) {
        this.keysEvaluated.add(key);
    }

    /**
     * Returns an <strong>immutable</strong> view of evaluated keys.
     * <p>
     * <strong>WARNING:</strong> The returned list cannot be modified. Any attempt
     * to call
     * {@code add()}, {@code remove()}, or other mutation methods will throw
     * {@code UnsupportedOperationException} at runtime.
     * <p>
     * To add keys, use {@link #addEvaluatedKey(String)} instead.
     * 
     * @return an immutable copy of the evaluated keys list (never null)
     */
    public List<String> getKeysEvaluated() {
        return List.copyOf(keysEvaluated);
    }

    /**
     * Set evaluated keys (for deserialization or reconstruction).
     */
    public void setKeysEvaluated(List<String> keysEvaluated) {
        this.keysEvaluated = keysEvaluated != null ? new ArrayList<>(keysEvaluated) : new ArrayList<>();
    }
}
