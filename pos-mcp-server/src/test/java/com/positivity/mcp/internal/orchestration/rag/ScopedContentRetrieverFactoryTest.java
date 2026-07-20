package com.positivity.mcp.internal.orchestration.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.RagScope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

class ScopedContentRetrieverFactoryTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ScopedContentRetrieverFactory.class, ScopedContentRetrieverFactoryContextTestConfig.class);

    @Test
        void createsRetrieverFilteredToNormalizedRagScope() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        when(embeddingStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("stock")));
        ScopedContentRetrieverFactory factory =
            new ScopedContentRetrieverFactory(embeddingStore);

        QueryDocumentRetriever retriever = factory.create(" INVENTORY ", 7, 0.42);
        retriever.retrieve("stock");

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(embeddingStore).similaritySearch(requestCaptor.capture());
        SearchRequest request = requestCaptor.getValue();
        assertThat(request.getQuery()).isEqualTo("stock");
        assertThat(request.getTopK()).isEqualTo(7);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.42);
        assertThat(request.getFilterExpression())
            .isEqualTo(new FilterExpressionBuilder().eq("rag_scope", "inventory").build());
    }

    @Test
        void createsRetrieverFilteredToMasterScopeWhenBlankRagScopeProvided() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        when(embeddingStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("anything")));
        ScopedContentRetrieverFactory factory =
            new ScopedContentRetrieverFactory(embeddingStore);

        QueryDocumentRetriever retriever = factory.create(" ", 3, 0.21);
        retriever.retrieve("anything");

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(embeddingStore).similaritySearch(requestCaptor.capture());
        SearchRequest request = requestCaptor.getValue();
        assertThat(request.getQuery()).isEqualTo("anything");
        assertThat(request.getTopK()).isEqualTo(3);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.21);
        assertThat(request.getFilterExpression())
            .isEqualTo(new FilterExpressionBuilder().eq("rag_scope", "master").build());
    }

    @Test
    void alphaProfileCreatesScopedContentRetrieverFactoryBean() {
        contextRunner.withPropertyValues("spring.profiles.active=alpha").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ScopedContentRetrieverFactory.class);
        });
    }

    @Test
    void testProfileAloneDoesNotCreateScopedContentRetrieverFactoryBean() {
        contextRunner.withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ScopedContentRetrieverFactory.class);
        });
    }

    static class ScopedContentRetrieverFactoryContextTestConfig {

        @Bean
        PgVectorStore embeddingStore() {
            return mock(PgVectorStore.class);
        }
    }
}

class RagScopeTest {

    @Test
    void normalizesBlankScopeToMaster() {
        assertEquals("master", RagScope.normalize(null));
        assertEquals("master", RagScope.normalize(" "));
        assertEquals("inventory", RagScope.normalize(" INVENTORY "));
    }
}
