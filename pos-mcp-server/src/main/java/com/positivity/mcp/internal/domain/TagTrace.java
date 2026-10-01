package com.positivity.mcp.internal.domain;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0068 §6 / spec §2.8: what one turn's tagging looked like, recorded on the {@link EvalTurnTrace}
 * in {@code shadow} and {@code enforce} (and in {@code off}, where it carries the heuristic answers
 * alone) so a tag can be judged against its heuristic before it is promoted.
 *
 * <p>Values are enum names, booleans or option labels: never message text (ADR-0068 §3.6, §4).
 *
 * @param mode {@code OFF}, {@code SHADOW} or {@code ENFORCE}
 * @param enforcedTags the tags listed in {@code mcp.tagging.enforced-tags}; empty outside {@code
 *     enforce}
 * @param providerModel the decision model that answered, when the provider was called
 * @param latencyMs the provider call's wall time, when it was called
 * @param fallbackReason why the turn took the heuristic answers instead of the model's, or null
 * @param stateTruncated whether the message was cut at {@code max-state-chars}
 * @param questionCount how many questions the provider was asked, when it was called (the cost of
 *     the wide request, for the bake-off)
 * @param requestBodyBytes the size of the request body sent, when the provider was called
 * @param optionListHash the hash of the option lists asked (domain options and entity keys), when
 *     the provider was called
 * @param tags one entry per tag either tagger answered, by wire name
 */
public record TagTrace(
        @NonNull String mode,
        @NonNull List<String> enforcedTags,
        @Nullable String providerModel,
        @Nullable Long latencyMs,
        @Nullable String fallbackReason,
        boolean stateTruncated,
        @Nullable Integer questionCount,
        @Nullable Integer requestBodyBytes,
        @Nullable String optionListHash,
        @NonNull List<TagEntry> tags) {

    public TagTrace {
        enforcedTags = List.copyOf(enforcedTags);
        tags = List.copyOf(tags);
    }

    /**
     * @param name the wire name ({@code simple_chat}, {@code entity_work-order}, ...)
     * @param actingValue the value consumers read, or null when neither tagger's answer acted
     * @param actingSource {@code HEURISTIC} or {@code JEV}
     * @param heuristicValue the heuristic answer, or null (the heuristic answers no entity tag)
     * @param heuristicRule the heuristic rule that fired, where the heuristic exposes one cheaply
     * @param modelValue the model's answer, or null when it did not answer
     * @param modelConfidence the model's confidence, or null
     * @param modelProbability the raw Noul {@code p} the model reported, or null
     * @param threshold the confidence threshold in effect for the tag
     * @param agree whether the two answers agree; null unless both answered
     */
    public record TagEntry(
            @NonNull String name,
            @Nullable String actingValue,
            @Nullable String actingSource,
            @Nullable String heuristicValue,
            @Nullable String heuristicRule,
            @Nullable String modelValue,
            @Nullable Double modelConfidence,
            @Nullable Double modelProbability,
            double threshold,
            @Nullable Boolean agree) {}
}
