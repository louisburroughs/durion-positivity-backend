package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * Prevents supplemental RAG retrieval failures from aborting the primary chat
 * response.
 */
final class ResilientContentRetriever implements QueryDocumentRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientContentRetriever.class);
    private static final int MAX_LOG_PREVIEW_LENGTH = 160;

    private final QueryDocumentRetriever delegate;
    private final String retrieverName;

    ResilientContentRetriever(@NonNull QueryDocumentRetriever delegate, @NonNull String retrieverName) {
        this.delegate = delegate;
        this.retrieverName = retrieverName;
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        long startNanos = System.nanoTime();
        String queryPreview = preview(queryText);
        LOGGER.debug(
                "RAG retrieval starting retriever={} chars={} preview=\"{}\"",
                retrieverName,
                queryText.length(),
                queryPreview);
        try {
            List<Document> content = delegate.retrieve(queryText);
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
