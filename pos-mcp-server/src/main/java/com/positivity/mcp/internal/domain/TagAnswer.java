package com.positivity.mcp.internal.domain;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0068 §1: one tag's value, its confidence and the tagger that produced it.
 *
 * <p>The value is typed at the seam ({@link QuestionTags} reads it back as a boolean, an enum name or
 * an option label) and is never message text: a Noul answer is {@code true}/{@code false}, a Choice
 * answer is one of the question's labels, a Score answer is one of its levels (ADR-0068 §3.6).
 *
 * @param value {@code true}/{@code false} for a Noul tag, an option label otherwise
 * @param confidence for a Noul answer {@code max(p, 1 - p)}; Jev's own confidence for Choice and
 *     Score; {@code 1.0} for every heuristic answer (a rule either fires or does not)
 * @param score a Score answer's probability-weighted level as Jev reports it, recorded but never used
 *     for the value (spec §2.2); null for other primitives and for heuristic answers
 * @param probability a Noul answer's raw {@code p} as Jev reports it; null otherwise
 * @param rule the heuristic rule that fired ({@code phrase:create po}, {@code cue:those},
 *     {@code keyword:users}), where the heuristic exposes one cheaply; null otherwise
 */
public record TagAnswer(
        @NonNull String value,
        double confidence,
        @NonNull TagSource source,
        @Nullable Double score,
        @Nullable Double probability,
        @Nullable String rule) {

    public TagAnswer {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0, 1]: " + confidence);
        }
    }

    public TagAnswer(@NonNull String value, double confidence, @NonNull TagSource source) {
        this(value, confidence, source, null, null, null);
    }

    public TagAnswer(@NonNull String value, double confidence, @NonNull TagSource source, @Nullable Double score) {
        this(value, confidence, source, score, null, null);
    }

    /** A heuristic answer: confidence {@code 1.0}, source {@link TagSource#HEURISTIC}, no rule id. */
    public static @NonNull TagAnswer heuristic(boolean value) {
        return heuristic(Boolean.toString(value), null);
    }

    /** A heuristic answer with the rule that fired ({@code phrase:create po}), or null when none is exposed. */
    public static @NonNull TagAnswer heuristic(boolean value, @Nullable String rule) {
        return heuristic(Boolean.toString(value), rule);
    }

    /** A heuristic answer: confidence {@code 1.0}, source {@link TagSource#HEURISTIC}. */
    public static @NonNull TagAnswer heuristic(@NonNull String value) {
        return heuristic(value, null);
    }

    public static @NonNull TagAnswer heuristic(@NonNull String value, @Nullable String rule) {
        return new TagAnswer(value, 1.0, TagSource.HEURISTIC, null, null, rule);
    }

    /**
     * A Noul answer from Jev: value {@code p >= 0.5}, confidence {@code max(p, 1 - p)} (ADR-0068 §1);
     * the raw {@code p} is kept for the trace.
     */
    public static @NonNull TagAnswer noul(double probability) {
        boolean value = probability >= 0.5;
        return new TagAnswer(
                Boolean.toString(value),
                Math.max(probability, 1.0 - probability),
                TagSource.JEV,
                null,
                probability,
                null);
    }

    /** True when the value is the Noul {@code true}. */
    public boolean isTrue() {
        return Boolean.parseBoolean(value);
    }
}
