package com.positivity.mcp.internal.orchestration.rag;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
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
        return EmbeddingStoreContentRetriever.builder()
                .displayName("rag-scope-" + normalizedScope)
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(metadataKey(RAG_SCOPE).isEqualTo(normalizedScope))
                .build();
    }
}
