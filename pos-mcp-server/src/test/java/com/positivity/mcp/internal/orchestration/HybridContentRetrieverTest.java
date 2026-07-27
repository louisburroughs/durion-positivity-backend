package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class HybridContentRetrieverTest {

    private static Document doc(String id, String text) {
        return new Document(id, text, Map.of("rag_scope", "master"));
    }

    private static QueryDocumentRetriever returning(Document... docs) {
        List<Document> fixed = List.of(docs);
        return queryText -> fixed;
    }

    @Test
    @DisplayName("RRF ranks by summed reciprocal rank so multi-source hits rise above single-source hits")
    void reciprocalRankFusion_ranksBySummedReciprocalRank() {
        Document d1 = doc("d1", "t1");
        Document d2a = doc("d2", "t2-from-a");
        Document d3 = doc("d3", "t3");
        Document d2b = doc("d2", "t2-from-b"); // same id as d2a, different content
        Document d4 = doc("d4", "t4");

        QueryDocumentRetriever a = returning(d1, d2a, d3); // ranks 1,2,3
        QueryDocumentRetriever b = returning(d2b, d4); // ranks 1,2

        List<Document> fused = HybridContentRetriever.reciprocalRankFusion(List.of(a, b), 10, 60)
                .retrieve("q");

        // d2 = 1/62 + 1/61 (both sources) > d1 = 1/61 > d4 = 1/62 > d3 = 1/63
        assertThat(fused.stream().map(Document::getId)).containsExactly("d2", "d1", "d4", "d3");
        // Dedup is by id: the first-seen instance (from source A) is kept.
        assertThat(fused.get(0).getText()).isEqualTo("t2-from-a");
    }

    @Test
    @DisplayName("RRF truncates to maxMergedResults")
    void reciprocalRankFusion_truncatesToMax() {
        QueryDocumentRetriever a = returning(doc("d1", "t1"), doc("d2", "t2"), doc("d3", "t3"));

        List<Document> fused =
                HybridContentRetriever.reciprocalRankFusion(List.of(a), 2, 60).retrieve("q");

        assertThat(fused.stream().map(Document::getId)).containsExactly("d1", "d2");
    }

    @Test
    @DisplayName("Insertion-order mode keeps first-seen order and de-dupes by id (unchanged behavior)")
    void insertionOrder_dedupesByIdKeepingFirstSeen() {
        QueryDocumentRetriever a = returning(doc("d1", "t1"), doc("d2", "t2-from-a"));
        QueryDocumentRetriever b = returning(doc("d2", "t2-from-b"), doc("d3", "t3"));

        List<Document> merged = new HybridContentRetriever(List.of(a, b), 10).retrieve("q");

        assertThat(merged.stream().map(Document::getId)).containsExactly("d1", "d2", "d3");
        assertThat(merged.get(1).getText()).isEqualTo("t2-from-a");
    }
}
