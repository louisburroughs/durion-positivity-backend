package com.positivity.mcp.internal.orchestration;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prevents supplemental RAG retrieval failures from aborting the primary chat
 * response.
 */
final class ResilientContentRetriever implements ContentRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientContentRetriever.class);

    private final ContentRetriever delegate;
    private final String retrieverName;

    ResilientContentRetriever(@NonNull ContentRetriever delegate, @NonNull String retrieverName) {
        this.delegate = delegate;
        this.retrieverName = retrieverName;
    }

    @Override
    public @NonNull List<Content> retrieve(@NonNull Query query) {
        try {
            return delegate.retrieve(query);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "RAG retrieval disabled for query because {} failed: {}",
                    retrieverName,
                    exception.getMessage(),
                    exception);
            return List.of();
        }
    }
}
