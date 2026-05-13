package com.positivity.mcp.internal.orchestration;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Merges content from multiple retrievers and de-duplicates by normalized
 * content text.
 */
final class HybridContentRetriever implements ContentRetriever {

    private final List<ContentRetriever> retrievers;
    private final int maxMergedResults;

    HybridContentRetriever(@NonNull List<ContentRetriever> retrievers, int maxMergedResults) {
        this.retrievers = List.copyOf(retrievers);
        this.maxMergedResults = Math.max(1, maxMergedResults);
    }

    @Override
    public @NonNull List<Content> retrieve(@NonNull Query query) {
        Map<String, Content> merged = new LinkedHashMap<>();
        for (ContentRetriever retriever : retrievers) {
            for (Content content : retriever.retrieve(query)) {
                merged.putIfAbsent(contentKey(content), content);
            }
        }
        return new ArrayList<>(merged.values()).stream().limit(maxMergedResults).toList();
    }

    private static @NonNull String contentKey(@NonNull Content content) {
        if (content.textSegment() == null || content.textSegment().text() == null) {
            return String.valueOf(content.hashCode());
        }
        return content.textSegment().text().replaceAll("\\s+", " ").trim();
    }
}
