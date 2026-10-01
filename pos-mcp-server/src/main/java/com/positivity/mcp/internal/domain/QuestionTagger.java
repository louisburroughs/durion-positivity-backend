package com.positivity.mcp.internal.domain;

import org.jspecify.annotations.NonNull;

/**
 * ADR-0068 §1: the one tagging seam in front of the executor LLM. An implementation answers the
 * closed tag set ({@link TagName}) for one user message and nothing else: the request is
 * caller-independent (ADR-0068 §4), so there is no caller, tenant or history parameter.
 *
 * <p>Implementations live in {@code internal.orchestration} ({@code HeuristicQuestionTagger},
 * {@code JevQuestionTagger}); this interface and its value types live here because {@code
 * internal.service} consumes the tags and already sits below {@code orchestration} in the package
 * graph (ADR-0068 §1, {@code packages_should_be_free_of_cycles}).
 */
public interface QuestionTagger {

    /**
     * Tags {@code message}. The heuristic implementation never throws; the decision-model one signals
     * a provider failure so the caller can fall back to the heuristic record for the whole turn
     * (ADR-0068 §2).
     */
    @NonNull
    QuestionTags tag(@NonNull String message);
}
