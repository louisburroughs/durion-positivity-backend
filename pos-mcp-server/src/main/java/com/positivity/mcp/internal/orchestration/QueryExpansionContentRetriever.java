package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;

/**
 * Expands user queries with a small set of deterministic paraphrases to improve
 * recall on wording variations without depending on an extra LLM call.
 */
final class QueryExpansionContentRetriever implements QueryDocumentRetriever {

    private final QueryDocumentRetriever delegate;
    private final int expandedQueryLimit;
    private final int maxMergedResults;

    QueryExpansionContentRetriever(
            @NonNull QueryDocumentRetriever delegate, int expandedQueryLimit, int maxMergedResults) {
        this.delegate = delegate;
        this.expandedQueryLimit = Math.max(1, expandedQueryLimit);
        this.maxMergedResults = Math.max(1, maxMergedResults);
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        List<String> expandedQueries = expand(queryText);
        Map<String, Document> merged = new LinkedHashMap<>();
        for (String expandedQuery : expandedQueries) {
            List<Document> retrieved = delegate.retrieve(expandedQuery);
            for (Document document : retrieved) {
                merged.putIfAbsent(contentKey(document), document);
            }
        }
        return merged.values().stream().limit(maxMergedResults).toList();
    }

    private @NonNull List<String> expand(@NonNull String queryText) {
        String normalized = normalize(queryText);
        List<String> expansions = new ArrayList<>();
        expansions.add(normalized);

        String keywordFocus = dropStopWords(normalized);
        if (!keywordFocus.isBlank() && !keywordFocus.equals(normalized)) {
            expansions.add(keywordFocus);
        }

        expansions.add("Find information about " + normalized);
        expansions.add("Show relevant documentation for " + normalized);

        return expansions.stream().distinct().limit(expandedQueryLimit).toList();
    }

    private static @NonNull String dropStopWords(@NonNull String text) {
        Set<String> stopWords = Set.of("the", "a", "an", "for", "to", "in", "on", "of", "and", "please");
        return tokenize(text).stream()
                .filter(token -> !stopWords.contains(token))
                .reduce("", (left, right) -> {
                    if (left.isEmpty()) {
                        return right;
                    }
                    return left + " " + right;
                });
    }

    private static @NonNull String contentKey(@NonNull Document document) {
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return String.valueOf(document.hashCode());
        }
        return normalize(text);
    }

    private static @NonNull String normalize(@NonNull String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static @NonNull List<String> tokenize(@NonNull String text) {
        return List.of(normalize(text).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).stream()
                .filter(token -> !token.isBlank())
                .toList();
    }
}
