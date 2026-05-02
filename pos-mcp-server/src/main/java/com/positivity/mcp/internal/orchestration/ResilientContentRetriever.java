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
    private static final int MAX_LOG_PREVIEW_LENGTH = 160;

    private final ContentRetriever delegate;
    private final String retrieverName;

    ResilientContentRetriever(@NonNull ContentRetriever delegate, @NonNull String retrieverName) {
        this.delegate = delegate;
        this.retrieverName = retrieverName;
    }

    @Override
    public @NonNull List<Content> retrieve(@NonNull Query query) {
        long startNanos = System.nanoTime();
        String queryPreview = preview(query.text());
        LOGGER.debug(
                "RAG retrieval starting retriever={} chars={} preview=\"{}\"",
                retrieverName,
                query.text().length(),
                queryPreview);
        try {
            List<Content> content = delegate.retrieve(query);
            LOGGER.debug(
                    "RAG retrieval completed retriever={} contentItems={} elapsedMs={} preview=\"{}\"",
                    retrieverName,
                    content.size(),
                    elapsedMs(startNanos),
                    queryPreview);
            return content;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "RAG retrieval failed retriever={} elapsedMs={} preview=\"{}\" error={}",
                    retrieverName,
                    elapsedMs(startNanos),
                    queryPreview,
                    exception.getMessage(),
                    exception);
            return List.of();
        }
    }

    private static @NonNull String preview(@NonNull String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_LOG_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_PREVIEW_LENGTH - 3) + "...";
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
