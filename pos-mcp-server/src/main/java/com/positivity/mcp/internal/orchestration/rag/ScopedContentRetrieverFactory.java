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

    /** Dense (vector) retriever, scope-filtered to {@code ragScope}. */
    public @NonNull QueryDocumentRetriever create(@Nullable String ragScope, int maxResults, double minScore) {
        String normalizedScope = RagScope.normalize(ragScope);
        var ragScopeFilter =
                new FilterExpressionBuilder().eq(RAG_SCOPE, normalizedScope).build();
        return queryText -> embeddingStore.similaritySearch(SearchRequest.builder()
                .query(queryText)
                .topK(maxResults)
                .similarityThreshold(minScore)
                .filterExpression(ragScopeFilter)
                .build());
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
