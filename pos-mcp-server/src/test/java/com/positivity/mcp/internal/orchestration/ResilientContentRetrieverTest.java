package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class ResilientContentRetrieverTest {

    @Test
    @DisplayName("retrieve returns delegate content when retrieval succeeds")
    void retrieve_returnsDelegateContent() {
        QueryDocumentRetriever delegate = mock(QueryDocumentRetriever.class);
        String query = "inventory stock";
        Document content = mock(Document.class);
        when(delegate.retrieve(query)).thenReturn(List.of(content));

        ResilientContentRetriever retriever = new ResilientContentRetriever(delegate, "test-retriever");

        assertThat(retriever.retrieve(query)).containsExactly(content);
    }

    @Test
    @DisplayName("retrieve returns empty list when embedding retrieval fails")
    void retrieve_returnsEmptyListWhenDelegateFails() {
        QueryDocumentRetriever delegate = mock(QueryDocumentRetriever.class);
        String query = "inventory stock";
        when(delegate.retrieve(query))
            .thenThrow(new RuntimeException("model \"nomic-embed-text\" not found, try pulling it first"));

        ResilientContentRetriever retriever = new ResilientContentRetriever(delegate, "test-retriever");

        assertThat(retriever.retrieve(query)).isEmpty();
    }
}
