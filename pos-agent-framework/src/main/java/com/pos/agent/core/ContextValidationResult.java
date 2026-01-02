package com.pos.agent.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Context validation result.
 */
public class ContextValidationResult {
    private final boolean sufficient;
    private final List<String> missingInputs;
    private final List<String> missingDecisions;
    private final Duration validationTime;

    public ContextValidationResult(boolean sufficient, List<String> missingInputs,
            List<String> missingDecisions, Duration validationTime) {
        this.sufficient = sufficient;
        this.missingInputs = missingInputs;
        this.missingDecisions = missingDecisions;
        this.validationTime = validationTime;
    }

    public boolean isSufficient() {
        return sufficient;
    }

    public List<String> getMissingInputs() {
        return new ArrayList<>(missingInputs);
    }

    public List<String> getMissingDecisions() {
        return new ArrayList<>(missingDecisions);
    }

    public Duration getValidationTime() {
        return validationTime;
    }

    public String getInsufficientContextMessage() {
        if (sufficient) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Context insufficient – re-anchor needed.\n");
        if (!missingInputs.isEmpty()) {
            sb.append("Missing inputs: ").append(String.join(", ", missingInputs)).append("\n");
        }
        if (!missingDecisions.isEmpty()) {
            sb.append("Missing decisions: ").append(String.join(", ", missingDecisions));
        }
        return sb.toString();
    }
}