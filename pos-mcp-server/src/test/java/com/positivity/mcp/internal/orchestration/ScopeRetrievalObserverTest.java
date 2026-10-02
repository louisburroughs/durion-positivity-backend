package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.positivity.mcp.internal.domain.ScopeTrace.RetrievedDocument;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.document.Document;

/** ADR-0069 §9: retrieval is observed for the scope trace, and never altered. */
class ScopeRetrievalObserverTest {

    private final RequestScopedUserContext requestContext = new RequestScopedUserContext();
    private final ToolInvocationRecorder recorder = mock(ToolInvocationRecorder.class);
    private final List<Document> retrieved = List.of(
            chunk("first chunk", "workorder.status-lifecycle"),
            chunk("second chunk", "workorder.status-lifecycle"),
            chunk("third chunk", "billing.invoices"),
            new Document("a chunk with no document id", Map.of("rag_scope", "master")),
            new Document("a master chunk", Map.of("document_id", "glossary.identifiers", "rag_scope", "master")),
            new Document("a chunk with no rag scope", Map.of("document_id", "order.codes")));
    private final QueryDocumentRetriever delegate = queryText -> retrieved;

    @AfterEach
    void clear() {
        requestContext.clear();
    }

    private static Document chunk(String text, String documentId) {
        return new Document(text, Map.of("document_id", documentId, "rag_scope", "workorder"));
    }

    @Test
    @DisplayName("with no scope published (mode off) it is a plain call-through: same list, nothing recorded")
    void noScope_callThrough() {
        List<Document> result =
                new ScopeRetrievalObserver(delegate, requestContext, recorder).retrieve("the work order");

        assertThat(result).isSameAs(retrieved);
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("with a scope published it records each document once, in rank order with its rag_scope, "
            + "and returns the same list untouched")
    void scopePublished_recordsDistinctDocumentsInRankOrder() {
        requestContext.recordScope(ScopeSet.empty());
        List<Document> before = List.copyOf(retrieved);

        List<Document> result =
                new ScopeRetrievalObserver(delegate, requestContext, recorder).retrieve("the work order");

        assertThat(result).isSameAs(retrieved);
        assertThat(result).containsExactlyElementsOf(before);
        // Two chunks of one document collapse to the first; a chunk without a document id is skipped;
        // the rag_scope is the chunk's own, null when it carries none. Order is the rank order.
        verify(recorder)
                .recordRetrievedDocuments(List.of(
                        new RetrievedDocument("workorder.status-lifecycle", "workorder"),
                        new RetrievedDocument("billing.invoices", "workorder"),
                        new RetrievedDocument("glossary.identifiers", "master"),
                        new RetrievedDocument("order.codes", null)));
    }

    @Test
    @DisplayName("an empty retrieval under a scope is recorded as no documents, which is a fact, not an absence")
    void emptyRetrieval_recordsNoDocuments() {
        requestContext.recordScope(ScopeSet.empty());

        List<Document> result =
                new ScopeRetrievalObserver(queryText -> List.of(), requestContext, recorder).retrieve("anything");

        assertThat(result).isEmpty();
        verify(recorder).recordRetrievedDocuments(List.<RetrievedDocument>of());
    }

    @Test
    @DisplayName("a recorder failure never reaches retrieval")
    void recorderFailure_isSwallowed() {
        requestContext.recordScope(ScopeSet.empty());
        doThrow(new IllegalStateException("trace store down"))
                .when(recorder)
                .recordRetrievedDocuments(ArgumentMatchers.<List<RetrievedDocument>>any());

        assertThat(new ScopeRetrievalObserver(delegate, requestContext, recorder).retrieve("the work order"))
                .isSameAs(retrieved);
    }

    @Test
    @DisplayName("without a request context or a recorder (hand-built managers) it is a plain call-through")
    void unwired_callThrough() {
        requestContext.recordScope(ScopeSet.empty());

        assertThat(new ScopeRetrievalObserver(delegate, null, recorder).retrieve("q"))
                .isSameAs(retrieved);
        assertThat(new ScopeRetrievalObserver(delegate, requestContext, null).retrieve("q"))
                .isSameAs(retrieved);
        verifyNoInteractions(recorder);
    }
}
