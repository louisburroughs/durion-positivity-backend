package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * #1180: compound questions (two information needs in one query) must not let
 * chunks serving the
 * dominant need crowd the other need's best chunk out of the final top-K cut.
 */
class RerankedContentRetrieverTest {

    private static final String COMPOUND_QUERY = "what does an invoice number look like and what permission is needed to read an invoice";
    private static final String SINGLE_NEED_QUERY = "what permission is needed to read an invoice";

    private static final String GLOSSARY_TEXT = "an invoice number will look like INV-2025-000123 with the INV prefix then year then sequence";

    private static Document doc(String id, String text) {
        return new Document(id, text, Map.of("rag_scope", "master"));
    }

    private static Document permissionDoc(int n) {
        // Contains sub-query B verbatim; near-duplicates differing only in the trailing
        // role name.
        return doc("perm-" + n, "what permission is needed to read an invoice granted for role r" + n);
    }

    private static QueryDocumentRetriever returning(Document... docs) {
        List<Document> fixed = List.of(docs);
        return queryText -> fixed;
    }

    /**
     * Five near-duplicate permission chunks ranked 1-5, the format chunk fused at
     * rank 6.
     */
    private static QueryDocumentRetriever fusedWithFormatChunkAtRankSix() {
        return returning(
                permissionDoc(1),
                permissionDoc(2),
                permissionDoc(3),
                permissionDoc(4),
                permissionDoc(5),
                doc("glossary", GLOSSARY_TEXT));
    }

    @Test
    @DisplayName("#1180 failure shape: without compound slots, the second need's best chunk is cut")
    void withoutCompoundSlots_secondNeedBestChunkIsCut() {
        var retriever = new RerankedContentRetriever(fusedWithFormatChunkAtRankSix(), 5, false, 3);

        List<Document> top = retriever.retrieve(COMPOUND_QUERY);

        assertThat(top).hasSize(5);
        assertThat(top.stream().map(Document::getId)).doesNotContain("glossary");
    }

    @Test
    @DisplayName("compound slots reserve the second need's best chunk a slot in the top-K")
    void compoundSlots_secondNeedBestChunkSurvivesCut() {
        var retriever = new RerankedContentRetriever(fusedWithFormatChunkAtRankSix(), 5, true, 3);

        List<Document> top = retriever.retrieve(COMPOUND_QUERY);

        assertThat(top).hasSize(5);
        assertThat(top.stream().map(Document::getId)).contains("glossary");
        // The dominant need's best chunk is still present — reservation adds, it does
        // not evict all.
        assertThat(top.stream().map(Document::getId)).contains("perm-1");
    }

    @Test
    @DisplayName("single-need queries never split: results identical with the feature on or off")
    void singleNeedQuery_unchanged() {
        var enabled = new RerankedContentRetriever(fusedWithFormatChunkAtRankSix(), 5, true, 3);
        var disabled = new RerankedContentRetriever(fusedWithFormatChunkAtRankSix(), 5, false, 3);

        assertThat(enabled.retrieve(SINGLE_NEED_QUERY)).isEqualTo(disabled.retrieve(SINGLE_NEED_QUERY));
    }

    @Test
    @DisplayName("compound query whose needs already both rank in the top-K: results unchanged")
    void compoundQuery_bothNeedsAlreadyInTopK_unchanged() {
        // Format chunk fused at rank 1; permission chunks 2-6. Both needs' best chunks
        // are in the
        // natural top-5, so reservation must not alter the selection.
        QueryDocumentRetriever delegate = returning(
                doc("glossary", GLOSSARY_TEXT),
                permissionDoc(1),
                permissionDoc(2),
                permissionDoc(3),
                permissionDoc(4),
                permissionDoc(5));
        var enabled = new RerankedContentRetriever(delegate, 5, true, 3);
        var disabled = new RerankedContentRetriever(delegate, 5, false, 3);

        List<Document> top = enabled.retrieve(COMPOUND_QUERY);

        assertThat(top).isEqualTo(disabled.retrieve(COMPOUND_QUERY));
        assertThat(top.stream().map(Document::getId)).contains("glossary");
    }

    @Test
    @DisplayName("candidate list not exceeding top-K is returned unchanged either way")
    void fewCandidates_unchanged() {
        QueryDocumentRetriever delegate = returning(permissionDoc(1), doc("glossary", GLOSSARY_TEXT));
        var enabled = new RerankedContentRetriever(delegate, 5, true, 3);
        var disabled = new RerankedContentRetriever(delegate, 5, false, 3);

        assertThat(enabled.retrieve(COMPOUND_QUERY)).isEqualTo(disabled.retrieve(COMPOUND_QUERY));
    }

    @Test
    @DisplayName("sub-query split: conjunction followed by a new question splits, noun 'and' does not")
    void splitSubQueries_heuristics() {
        assertThat(RerankedContentRetriever.splitSubQueries(COMPOUND_QUERY, 3))
                .containsExactly(
                        "what does an invoice number look like", "what permission is needed to read an invoice");
        // 'and' joining noun phrases is not a second information need.
        assertThat(RerankedContentRetriever.splitSubQueries("returns and refunds policy for special orders", 3))
                .hasSize(1);
        // Sentence boundary splits.
        assertThat(RerankedContentRetriever.splitSubQueries("what is a VIN? how do I decode the check digit", 3))
                .containsExactly("what is a VIN?", "how do I decode the check digit");
        assertThat(RerankedContentRetriever.splitSubQueries(
                "where is the invoice PLUS ALSO what permission is required", 3))
                .containsExactly("where is the invoice", "ALSO what permission is required");
        assertThat(RerankedContentRetriever.splitSubQueries("where is the VIN; how do I decode it", 3))
                .containsExactly("where is the VIN;", "how do I decode it");
        assertThat(RerankedContentRetriever.splitSubQueries("where is the VIN? and refunds policy details", 3))
                .containsExactly("where is the VIN?", "and refunds policy details");
        // Trailing fragments that are too short to be an information need suppress the
        // split.
        assertThat(RerankedContentRetriever.splitSubQueries("invoice number format and what else", 3))
                .hasSize(1);
    }

    @Test
    @DisplayName("sub-query split honors the max-sub-queries cap")
    void splitSubQueries_capped() {
        String query = "what is a VIN and what is a SKU and what is a GL code and what is a PO number";

        assertThat(RerankedContentRetriever.splitSubQueries(query, 3)).hasSize(3);
    }
}
