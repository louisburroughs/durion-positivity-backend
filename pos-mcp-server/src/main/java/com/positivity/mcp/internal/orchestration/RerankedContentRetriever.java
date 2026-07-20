package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;

/**
 * Re-ranks retrieved content by lexical overlap and source rank.
 */
final class RerankedContentRetriever implements QueryDocumentRetriever {

    private final QueryDocumentRetriever delegate;
    private final int topK;

    RerankedContentRetriever(@NonNull QueryDocumentRetriever delegate, int topK) {
        this.delegate = delegate;
        this.topK = Math.max(1, topK);
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        List<Document> candidates = delegate.retrieve(queryText);
        Set<String> queryTokens = tokens(queryText);

        Map<String, RankedContent> deduped = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            Document document = candidates.get(index);
            String key = contentKey(document);
            RankedContent candidate = rankContent(document, queryText, queryTokens, index);
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
            @NonNull Document document, @NonNull String queryText, @NonNull Set<String> queryTokens, int index) {
        String contentText = contentText(document);
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
        return new RankedContent(document, totalScore, index);
    }

    private static @NonNull String contentKey(@NonNull Document document) {
        String text = contentText(document);
        if (text.isBlank()) {
            return String.valueOf(document.hashCode());
        }
        return text;
    }

    private static @NonNull String contentText(@NonNull Document document) {
        if (document.getText() == null) {
            return "";
        }
        return normalize(document.getText());
    }

    private static @NonNull String normalize(@NonNull String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static @NonNull Set<String> tokens(@NonNull String text) {
        return Set.copyOf(List.of(normalize(text).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).stream()
                .filter(token -> !token.isBlank())
                .toList());
    }

    private record RankedContent(@NonNull Document content, double score, int originalRank) {}
}
