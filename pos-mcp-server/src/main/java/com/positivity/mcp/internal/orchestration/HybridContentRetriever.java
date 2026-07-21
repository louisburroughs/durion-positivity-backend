package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;

/**
 * Merges content from multiple retrievers and de-duplicates by normalized
 * content text.
 */
final class HybridContentRetriever implements QueryDocumentRetriever {

    private final List<QueryDocumentRetriever> retrievers;
    private final int maxMergedResults;

    HybridContentRetriever(@NonNull List<QueryDocumentRetriever> retrievers, int maxMergedResults) {
        this.retrievers = List.copyOf(retrievers);
        this.maxMergedResults = Math.max(1, maxMergedResults);
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        Map<String, Document> merged = new LinkedHashMap<>();
        for (QueryDocumentRetriever retriever : retrievers) {
            for (Document document : retriever.retrieve(queryText)) {
                merged.putIfAbsent(contentKey(document), document);
            }
        }
        return new ArrayList<>(merged.values()).stream().limit(maxMergedResults).toList();
    }

    private static @NonNull String contentKey(@NonNull Document document) {
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return String.valueOf(document.hashCode());
        }
        return text.replaceAll("\\s+", " ").trim();
    }
}
