package com.positivity.mcp.internal.orchestration.rag;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import com.positivity.mcp.internal.domain.RagScope;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("alpha")
public class ScopedContentRetrieverFactory {

    private static final String RAG_SCOPE = "rag_scope";

    private final PgVectorEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;

    public ScopedContentRetrieverFactory(
            @NonNull PgVectorEmbeddingStore embeddingStore, @NonNull EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    public @NonNull ContentRetriever create(@Nullable String ragScope, int maxResults, double minScore) {
        String normalizedScope = RagScope.normalize(ragScope);
        Filter ragScopeFilter = metadataKey(RAG_SCOPE).isEqualTo(normalizedScope);
        return EmbeddingStoreContentRetriever.builder()
                .displayName("rag-scope-" + normalizedScope)
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(ragScopeFilter)
                .build();
    }
}
