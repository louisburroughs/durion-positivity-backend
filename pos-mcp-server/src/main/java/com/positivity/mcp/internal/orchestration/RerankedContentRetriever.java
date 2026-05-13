package com.positivity.mcp.internal.orchestration;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Re-ranks retrieved content by lexical overlap and source rank.
 */
final class RerankedContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final int topK;

    RerankedContentRetriever(@NonNull ContentRetriever delegate, int topK) {
        this.delegate = delegate;
        this.topK = Math.max(1, topK);
    }

    @Override
    public @NonNull List<Content> retrieve(@NonNull Query query) {
        List<Content> candidates = delegate.retrieve(query);
        Set<String> queryTokens = tokens(query.text());

        Map<String, RankedContent> deduped = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            Content content = candidates.get(index);
            String key = contentKey(content);
            RankedContent candidate = rankContent(content, query.text(), queryTokens, index);
            deduped.merge(key, candidate, (left, right) -> left.score() >= right.score() ? left : right);
        }

        return deduped.values().stream()
                .sorted(Comparator.comparingDouble(RankedContent::score)
                        .reversed()
                        .thenComparingInt(RankedContent::originalRank))
                .limit(topK)
                .map(RankedContent::content)
                .toList();
    }

    private static @NonNull RankedContent rankContent(
            @NonNull Content content, @NonNull String queryText, @NonNull Set<String> queryTokens, int index) {
        String contentText = contentText(content);
        Set<String> contentTokens = tokens(contentText);
        int overlap = 0;
        for (String queryToken : queryTokens) {
            if (contentTokens.contains(queryToken)) {
                overlap++;
            }
        }

        double lexicalScore = queryTokens.isEmpty() ? 0.0 : (double) overlap / queryTokens.size();
        double phraseBoost =
                contentText.toLowerCase(Locale.ROOT).contains(queryText.toLowerCase(Locale.ROOT)) ? 0.2 : 0.0;
        double rankScore = 1.0 / (index + 1);
        double totalScore = (0.6 * lexicalScore) + (0.3 * rankScore) + phraseBoost;
        return new RankedContent(content, totalScore, index);
    }

    private static @NonNull String contentKey(@NonNull Content content) {
        String text = contentText(content);
        if (text.isBlank()) {
            return String.valueOf(content.hashCode());
        }
        return text;
    }

    private static @NonNull String contentText(@NonNull Content content) {
        if (content.textSegment() == null || content.textSegment().text() == null) {
            return "";
        }
        return normalize(content.textSegment().text());
    }

    private static @NonNull String normalize(@NonNull String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static @NonNull Set<String> tokens(@NonNull String text) {
        return Set.copyOf(List.of(normalize(text).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).stream()
                .filter(token -> !token.isBlank())
                .toList());
    }

    private record RankedContent(@NonNull Content content, double score, int originalRank) {}
}
