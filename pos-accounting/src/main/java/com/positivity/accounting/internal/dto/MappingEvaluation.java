package com.positivity.accounting.internal.dto;

import java.util.ArrayList;
import java.util.List;

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
}
