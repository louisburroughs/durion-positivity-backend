package com.positivity.mcp.internal.client;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One validated System One answer (spec §2.2), typed by primitive. Only numbers and option labels
 * the request itself defined ever get here: an unknown label or an out-of-range probability is a
 * provider failure before an answer exists (ADR-0068 §3.6).
 */
public sealed interface JevAnswer {

    /** @param probability the yes-probability {@code p}, within {@code [0, 1]} */
    record Noul(double probability) implements JevAnswer {}

    /**
     * @param label the chosen option, one of the question's labels
     * @param probabilities per-option distribution over the question's labels; may be empty
     * @param confidence Jev's own confidence, within {@code [0, 1]}
     */
    record Choice(@NonNull String label, @NonNull Map<String, Double> probabilities, double confidence)
            implements JevAnswer {
        public Choice {
            probabilities = Map.copyOf(probabilities);
        }
    }

    /**
     * @param level the level with the highest probability (ties: the higher level)
     * @param probabilities per-level distribution keyed by level label
     * @param confidence Jev's own confidence, within {@code [0, 1]}
     * @param score the probability-weighted level as reported, recorded and never used for the value
     */
    record Score(
            @NonNull String level,
            @NonNull Map<String, Double> probabilities,
            double confidence,
            @Nullable Double score)
            implements JevAnswer {
        public Score {
            probabilities = Map.copyOf(probabilities);
        }
    }
}
