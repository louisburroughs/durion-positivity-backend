package com.positivity.mcp.internal.orchestration.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.HybridRetrievalProperties;
import com.positivity.mcp.internal.domain.RagScope;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("alpha")
public class ScopedContentRetrieverFactory {

    private static final String RAG_SCOPE = "rag_scope";

    private final PgVectorStore embeddingStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HybridRetrievalProperties hybridProperties;

    public ScopedContentRetrieverFactory(
            @NonNull PgVectorStore embeddingStore,
            @NonNull JdbcTemplate jdbcTemplate,
            @NonNull ObjectMapper objectMapper,
            @NonNull HybridRetrievalProperties hybridProperties) {
        this.embeddingStore = embeddingStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.hybridProperties = hybridProperties;
    }

    /**
     * Dense (vector) retriever, scope-filtered to {@code ragScope}.
     *
     * <p>#1124 item 4: the {@code master} scope is the <em>catch-all</em> agent (resolved whenever the
     * selected tools are shared or span multiple domains — i.e. most general questions), not a literal
     * doc scope. A strict {@code rag_scope = 'master'} filter there made every domain doc
     * (order/pricing/tax/…) structurally unreachable, so cross-domain questions retrieved nothing and
     * the assistant refused or fabricated. When the scope resolves to {@code master} we therefore drop
     * the scope filter and search all scopes; specific domain scopes keep their narrow filter.
     * Permission-gated docs are still excluded downstream by {@code PermissionAwareMetadataFilter}.
     */
    public @NonNull QueryDocumentRetriever create(@Nullable String ragScope, int maxResults, double minScore) {
        String normalizedScope = RagScope.normalize(ragScope);
        var ragScopeFilter = RagScope.MASTER.equals(normalizedScope)
                ? null
                : new FilterExpressionBuilder().eq(RAG_SCOPE, normalizedScope).build();
        return queryText -> {
            SearchRequest.Builder request = SearchRequest.builder()
                    .query(queryText)
                    .topK(maxResults)
                    .similarityThreshold(minScore);
            if (ragScopeFilter != null) {
                request.filterExpression(ragScopeFilter);
            }
            return embeddingStore.similaritySearch(request.build());
        };
    }

    /**
     * #784: lexical (Postgres FTS) retriever, scope-filtered like {@link #create}. Present only when
     * {@code mcp.rag.hybrid.lexical-enabled=true}; otherwise empty, so callers transparently fall back
     * to the dense-only path.
     */
    public @NonNull Optional<QueryDocumentRetriever> createLexical(@Nullable String ragScope) {
        if (!hybridProperties.lexicalEnabled()) {
            return Optional.empty();
        }
        return Optional.of(new LexicalDocumentRetriever(
                jdbcTemplate, objectMapper, ragScope, hybridProperties.lexicalMaxResults()));
    }

    /** #784: RRF rank constant for fusing dense + lexical results. */
    public int rrfK() {
        return hybridProperties.rrfK();
    }
}
