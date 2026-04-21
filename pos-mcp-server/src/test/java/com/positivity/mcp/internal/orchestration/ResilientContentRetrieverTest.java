package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResilientContentRetrieverTest {

    @Test
    @DisplayName("retrieve returns delegate content when retrieval succeeds")
    void retrieve_returnsDelegateContent() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        Query query = Query.from("inventory stock");
        Content content = mock(Content.class);
        when(delegate.retrieve(query)).thenReturn(List.of(content));

        ResilientContentRetriever retriever = new ResilientContentRetriever(delegate, "test-retriever");

        assertThat(retriever.retrieve(query)).containsExactly(content);
    }

    @Test
    @DisplayName("retrieve returns empty list when embedding retrieval fails")
    void retrieve_returnsEmptyListWhenDelegateFails() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        Query query = Query.from("inventory stock");
        when(delegate.retrieve(query))
                .thenThrow(new ModelNotFoundException("model \"nomic-embed-text\" not found, try pulling it first"));

        ResilientContentRetriever retriever = new ResilientContentRetriever(delegate, "test-retriever");

        assertThat(retriever.retrieve(query)).isEmpty();
    }
}
