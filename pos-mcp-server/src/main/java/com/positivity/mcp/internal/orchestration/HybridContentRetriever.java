package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;

/**
 * Merges content from multiple retrievers under a selectable fusion strategy, de-duplicating by
 * document id (falling back to normalized content when no id is present).
 *
 * <ul>
 *   <li>{@link FusionMode#INSERTION_ORDER} — first-seen wins, results kept in retriever/insertion
 *       order (the original dense + query-expansion hybrid behavior).
 *   <li>{@link FusionMode#RECIPROCAL_RANK} — Reciprocal Rank Fusion (#784): each document scores
 *       {@code Σ 1/(k + rank_i)} across retrievers, so a document ranked highly by several sources
 *       rises. Fuses on rank, not raw score, so dense cosine and lexical {@code ts_rank} values need
 *       no cross-scale calibration.
 * </ul>
 */
final class HybridContentRetriever implements QueryDocumentRetriever {

    enum FusionMode {
        INSERTION_ORDER,
        RECIPROCAL_RANK
    }

    private final List<QueryDocumentRetriever> retrievers;
    private final int maxMergedResults;
    private final FusionMode fusionMode;
    private final int rrfK;

    HybridContentRetriever(@NonNull List<QueryDocumentRetriever> retrievers, int maxMergedResults) {
        this(retrievers, maxMergedResults, FusionMode.INSERTION_ORDER, 60);
    }

    private HybridContentRetriever(
            @NonNull List<QueryDocumentRetriever> retrievers, int maxMergedResults, FusionMode fusionMode, int rrfK) {
        this.retrievers = List.copyOf(retrievers);
        this.maxMergedResults = Math.max(1, maxMergedResults);
        this.fusionMode = fusionMode;
        this.rrfK = Math.max(1, rrfK);
    }

    /**
     * #784: fuse the given retrievers with Reciprocal Rank Fusion using rank constant {@code rrfK}
     * (RRF default 60).
     */
    static @NonNull HybridContentRetriever reciprocalRankFusion(
            @NonNull List<QueryDocumentRetriever> retrievers, int maxMergedResults, int rrfK) {
        return new HybridContentRetriever(retrievers, maxMergedResults, FusionMode.RECIPROCAL_RANK, rrfK);
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        return fusionMode == FusionMode.RECIPROCAL_RANK
                ? fuseByReciprocalRank(queryText)
                : mergeInInsertionOrder(queryText);
    }

    private @NonNull List<Document> mergeInInsertionOrder(@NonNull String queryText) {
        Map<String, Document> merged = new LinkedHashMap<>();
        for (QueryDocumentRetriever retriever : retrievers) {
            for (Document document : retriever.retrieve(queryText)) {
                merged.putIfAbsent(dedupKey(document), document);
            }
        }
        return new ArrayList<>(merged.values()).stream().limit(maxMergedResults).toList();
    }

    private @NonNull List<Document> fuseByReciprocalRank(@NonNull String queryText) {
        Map<String, Double> scoreByKey = new HashMap<>();
        // Insertion order preserved for a stable tie-break among equal RRF scores.
        Map<String, Document> documentByKey = new LinkedHashMap<>();
        for (QueryDocumentRetriever retriever : retrievers) {
            List<Document> ranked = retriever.retrieve(queryText);
            for (int index = 0; index < ranked.size(); index++) {
                Document document = ranked.get(index);
                String key = dedupKey(document);
                documentByKey.putIfAbsent(key, document);
                scoreByKey.merge(key, 1.0 / (rrfK + (index + 1)), Double::sum);
            }
        }
        return documentByKey.keySet().stream()
                .sorted(Comparator.comparingDouble((String key) -> scoreByKey.getOrDefault(key, 0.0))
                        .reversed())
                .map(documentByKey::get)
                .limit(maxMergedResults)
                .toList();
    }

    private static @NonNull String dedupKey(@NonNull Document document) {
        String id = document.getId();
        if (id != null && !id.isBlank()) {
            return "id:" + id;
        }
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return "hash:" + document.hashCode();
        }
        return "content:" + text.replaceAll("\\s+", " ").trim();
    }
}
