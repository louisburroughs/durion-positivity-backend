package com.positivity.mcp.internal.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0069 §9: what one turn's scope resolution looked like, recorded on the {@link EvalTurnTrace}
 * in {@code shadow} and {@code enforce} so the scope can be judged before any consumer acts on it.
 *
 * <p>Counts, keys and enums only. A seed is an entity key and the way it was recognised; the text
 * that matched is never recorded here (the trace already holds the whole user message, and the scope
 * itself must stay free of user text).
 *
 * @param mode {@code SHADOW} or {@code ENFORCE}
 * @param enforced the consumers that act on the scope ({@code RAG}, {@code TOOLS}, {@code CARD})
 * @param graphHash the content hash of the graph snapshot the scope was resolved against
 * @param confidence {@code NONE}, {@code LOW} or {@code HIGH}
 * @param addedTools tools a consumer added to the request because of the scope
 * @param ragFilterApplied whether the scope narrowed this turn's retrieval
 * @param calledToolsInScope of {@code calledTools}, the calls to a tool that was in scope; both are
 *     computed when the turn completes
 * @param calledTools the tool calls the model made
 * @param retrievedDocsInScope of {@code retrievedDocs}, the documents that were in scope
 * @param retrievedDocs the distinct documents of the final top-K handed to the model; null when
 *     retrieval was not observed for the turn
 */
public record ScopeTrace(
        @NonNull String mode,
        @NonNull List<String> enforced,
        @NonNull String graphHash,
        @NonNull Instant graphBuiltAt,
        @NonNull String confidence,
        @NonNull List<SeedTrace> seeds,
        int entityCount,
        int toolCount,
        int documentCount,
        int screenCount,
        int addedTools,
        boolean ragFilterApplied,
        @Nullable Integer calledToolsInScope,
        @Nullable Integer calledTools,
        @Nullable Integer retrievedDocsInScope,
        @Nullable Integer retrievedDocs) {

    public ScopeTrace {
        enforced = List.copyOf(enforced);
        seeds = List.copyOf(seeds);
    }

    /** @param matchKind {@code IDENTIFIER}, {@code EXACT_TERM}, {@code AMBIGUOUS_TERM}, {@code GLOSSARY_TERM} or {@code FOLDED_TERM} */
    public record SeedTrace(@NonNull String entity, @NonNull String matchKind) {}
}
