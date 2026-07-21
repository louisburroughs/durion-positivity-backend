package com.positivity.mcp.internal.orchestration.rag;

import com.positivity.mcp.internal.domain.RagScope;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("alpha")
public class ScopedContentRetrieverFactory {

    private static final String RAG_SCOPE = "rag_scope";

    private final PgVectorStore embeddingStore;

    public ScopedContentRetrieverFactory(@NonNull PgVectorStore embeddingStore) {
        this.embeddingStore = embeddingStore;
    }

    public @NonNull QueryDocumentRetriever create(@Nullable String ragScope, int maxResults, double minScore) {
        String normalizedScope = RagScope.normalize(ragScope);
        var ragScopeFilter = new FilterExpressionBuilder().eq(RAG_SCOPE, normalizedScope).build();
        return queryText -> embeddingStore.similaritySearch(SearchRequest.builder()
                .query(queryText)
                .topK(maxResults)
                .similarityThreshold(minScore)
                .filterExpression(ragScopeFilter)
                .build());
    }
}
