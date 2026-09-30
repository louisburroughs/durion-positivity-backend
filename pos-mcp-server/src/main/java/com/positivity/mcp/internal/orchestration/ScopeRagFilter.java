package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.domain.RagScope;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.scopegraph.ScopeConsumers;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * ADR-0069 §6, RAG consumer: the request-scoped hook that narrows the fused candidate pool to the
 * turn's scope, installed beside {@code permissionFiltered} after fusion and before the top-K
 * rerank cut, so the top-K is chosen from in-scope survivors.
 *
 * <p>Installed only where the {@code rag} consumer is in {@code enforce}; the agent's retrievers are
 * then built over ALL scopes (the way the {@code master} agent's are today) and this hook does the
 * narrowing per request:
 *
 * <ul>
 *   <li>scope confidence {@code HIGH}: keep a chunk whose {@code document_id} is one of the scope's
 *       documents, or whose {@code rag_scope} is {@code master};
 *   <li>otherwise ({@code LOW}, {@code NONE}, or no scope published): re-apply today's eligibility,
 *       {@code rag_scope IN (agent scope, master)}, keeping everything when the agent's scope is
 *       {@code master}. That is a fallback, counted under {@code mcp.scope.fallback{consumer=rag}}.
 * </ul>
 *
 * <p>The hook can only narrow: it never admits a chunk the retrievers did not fetch. Whether it
 * narrowed is recorded on the request-scoped holder ({@code ragFilterApplied}) for the trace and
 * telemetry. The scope is read per request, never captured at agent-build time, so a cached agent
 * cannot apply a prior request's scope.
 */
final class ScopeRagFilter implements QueryDocumentRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScopeRagFilter.class);

    /** The chunk metadata keys written at ingest ({@code DocumentEmbeddingIngestor}, {@code StaticRagPreloadServiceImpl}). */
    static final String DOCUMENT_ID = "document_id";

    static final String RAG_SCOPE = "rag_scope";

    private final QueryDocumentRetriever delegate;
    private final String agentRagScope;
    private final RequestScopedUserContext requestScopedUserContext;
    private final ScopeConsumers scopeConsumers;

    /**
     * @param agentRagScope the scope the agent's tools resolve to ({@code resolveRagScopeForTools}),
     *     which is today's eligibility for the fallback
     */
    ScopeRagFilter(
            @NonNull QueryDocumentRetriever delegate,
            @Nullable String agentRagScope,
            @NonNull RequestScopedUserContext requestScopedUserContext,
            @NonNull ScopeConsumers scopeConsumers) {
        this.delegate = delegate;
        this.agentRagScope = RagScope.normalize(agentRagScope);
        this.requestScopedUserContext = requestScopedUserContext;
        this.scopeConsumers = scopeConsumers;
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        List<Document> candidates = delegate.retrieve(queryText);
        Optional<ScopeSet> scope = requestScopedUserContext.currentScope();
        boolean applied = scope.isPresent() && scopeConsumers.ragActsOn(scope.get());
        requestScopedUserContext.recordScopeRagFilterApplied(applied);
        List<Document> kept = applied
                ? keepInScope(candidates, Set.copyOf(scope.get().documentIds()))
                : keepEligibleToday(candidates, agentRagScope);
        if (!applied) {
            scopeConsumers.recordFallback(Consumer.RAG);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP scope RAG filter applied={} agentScope={} candidates={} kept={}",
                    applied,
                    agentRagScope,
                    candidates.size(),
                    kept.size());
        }
        return kept;
    }

    /** ADR-0069 §6: {@code document_id IN (scope docs) OR rag_scope = 'master'}. */
    static @NonNull List<Document> keepInScope(@NonNull List<Document> candidates, @NonNull Set<String> documentIds) {
        return candidates.stream()
                .filter(document -> {
                    String documentId = metadata(document, DOCUMENT_ID);
                    return (documentId != null && documentIds.contains(documentId))
                            || RagScope.MASTER.equals(ragScopeOf(document));
                })
                .toList();
    }

    /**
     * Today's eligibility ({@code ScopedContentRetrieverFactory.create}, {@code
     * LexicalDocumentRetriever}): every scope for a {@code master} agent, else the agent's own scope
     * plus {@code master}.
     */
    static @NonNull List<Document> keepEligibleToday(
            @NonNull List<Document> candidates, @NonNull String agentRagScope) {
        if (RagScope.MASTER.equals(agentRagScope)) {
            return candidates;
        }
        return candidates.stream()
                .filter(document -> {
                    String ragScope = ragScopeOf(document);
                    return agentRagScope.equals(ragScope) || RagScope.MASTER.equals(ragScope);
                })
                .toList();
    }

    /** Null when the chunk carries no {@code rag_scope}: it is then eligible for no scoped agent. */
    private static @Nullable String ragScopeOf(@NonNull Document document) {
        String raw = metadata(document, RAG_SCOPE);
        return raw == null ? null : RagScope.normalize(raw);
    }

    private static @Nullable String metadata(@NonNull Document document, @NonNull String key) {
        Object value = document.getMetadata().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    @Override
    public String toString() {
        return "ScopeRagFilter{agentRagScope=" + agentRagScope + ", delegate=" + delegate + '}';
    }
}
