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
        long startNanos = System.nanoTime();
        try {
            List<Content> content = delegate.retrieve(query);
            LOGGER.info(
                    "RAG retrieval {} returned {} content items in {} ms",
                    retrieverName,
                    content.size(),
                    elapsedMs(startNanos));
            return content;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "RAG retrieval disabled for query because {} failed after {} ms: {}",
                    retrieverName,
                    elapsedMs(startNanos),
                    exception.getMessage(),
                    exception);
            return List.of();
        }
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
