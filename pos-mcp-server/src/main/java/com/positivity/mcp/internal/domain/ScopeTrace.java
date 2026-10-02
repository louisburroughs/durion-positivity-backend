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
 * <p>The identity lists ({@code retrievedDocuments}, {@code scopeDocumentIds}, {@code
 * scopeToolNames}, {@code addedToolNames}) exist so the §9 promotion gate for the {@code rag}
 * consumer can be computed offline from shadow traces: knowing <em>how many</em> retrieved documents
 * were in scope cannot say whether the filter would have kept the <em>right</em> one. Every entry is a
 * platform definition (ADR-0069 §8): a RAG {@code document_id}, a {@code rag_scope} name or an
 * {@code mcp_tool.name}. None of them is message text or instance data. All four are null in a
 * payload written before they existed; the two scope lists are bounded ({@link #IDENTITY_LIST_CAP})
 * and say when they were cut.
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
 * @param retrievedDocuments the final top-K handed to the model, in rank order, one entry per
 *     distinct {@code document_id} (chunks of one document collapse to the first occurrence, so a
 *     single retrieval yields at most K entries); null when retrieval was not observed for the turn
 * @param scopeDocumentIds the scope's {@code document_id}s, in scope order, at most {@link
 *     #IDENTITY_LIST_CAP}
 * @param scopeDocumentIdsTruncated whether {@code scopeDocumentIds} was cut at the cap
 * @param scopeToolNames the {@code mcp_tool.name} of every tool in scope, facade and discovered, in
 *     expansion order, at most {@link #IDENTITY_LIST_CAP}
 * @param scopeToolNamesTruncated whether {@code scopeToolNames} was cut at the cap
 * @param addedToolNames the names behind {@code addedTools}
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
        @Nullable Integer retrievedDocs,
        @Nullable List<RetrievedDocument> retrievedDocuments,
        @Nullable List<String> scopeDocumentIds,
        boolean scopeDocumentIdsTruncated,
        @Nullable List<String> scopeToolNames,
        boolean scopeToolNamesTruncated,
        @Nullable List<String> addedToolNames) {

    /** The most entries {@code scopeDocumentIds} and {@code scopeToolNames} each carry. */
    public static final int IDENTITY_LIST_CAP = 64;

    public ScopeTrace {
        enforced = List.copyOf(enforced);
        seeds = List.copyOf(seeds);
        retrievedDocuments = retrievedDocuments == null ? null : List.copyOf(retrievedDocuments);
        scopeDocumentIds = scopeDocumentIds == null ? null : List.copyOf(scopeDocumentIds);
        scopeToolNames = scopeToolNames == null ? null : List.copyOf(scopeToolNames);
        addedToolNames = addedToolNames == null ? null : List.copyOf(addedToolNames);
    }

    /** The pre-identity shape (counts only): every identity list null, nothing truncated. */
    public ScopeTrace(
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
        this(
                mode,
                enforced,
                graphHash,
                graphBuiltAt,
                confidence,
                seeds,
                entityCount,
                toolCount,
                documentCount,
                screenCount,
                addedTools,
                ragFilterApplied,
                calledToolsInScope,
                calledTools,
                retrievedDocsInScope,
                retrievedDocs,
                null,
                null,
                false,
                null,
                false,
                null);
    }

    /** @param matchKind {@code IDENTIFIER}, {@code EXACT_TERM}, {@code AMBIGUOUS_TERM}, {@code GLOSSARY_TERM} or {@code FOLDED_TERM} */
    public record SeedTrace(@NonNull String entity, @NonNull String matchKind) {}

    /**
     * One distinct document of a retrieval's final top-K: its {@code document_id} and the {@code
     * rag_scope} its chunk carried (null when the chunk had none). Both are ingest-time definitions,
     * never content.
     */
    public record RetrievedDocument(
            @NonNull String documentId, @Nullable String ragScope) {}
}
