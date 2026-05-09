package com.positivity.mcp.internal.orchestration.rag;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.lang.reflect.Field;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class ScopedContentRetrieverFactoryTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ScopedContentRetrieverFactory.class, ScopedContentRetrieverFactoryContextTestConfig.class);

    @Test
    void createsRetrieverFilteredToNormalizedRagScope() throws Exception {
        ScopedContentRetrieverFactory factory =
                new ScopedContentRetrieverFactory(mock(PgVectorEmbeddingStore.class), mock(EmbeddingModel.class));

        ContentRetriever retriever = factory.create(" INVENTORY ", 7, 0.42);

        assertThat(retriever).isInstanceOf(EmbeddingStoreContentRetriever.class);
        EmbeddingStoreContentRetriever embeddingRetriever = (EmbeddingStoreContentRetriever) retriever;
        assertThat(filterProvider(embeddingRetriever).apply(Query.from("stock")))
                .isEqualTo(metadataKey("rag_scope").isEqualTo("inventory"));
        assertThat(maxResultsProvider(embeddingRetriever).apply(Query.from("stock"))).isEqualTo(7);
        assertThat(minScoreProvider(embeddingRetriever).apply(Query.from("stock"))).isEqualTo(0.42);
    }

    @Test
    void createsRetrieverFilteredToMasterScopeWhenBlankRagScopeProvided() throws Exception {
        ScopedContentRetrieverFactory factory =
                new ScopedContentRetrieverFactory(mock(PgVectorEmbeddingStore.class), mock(EmbeddingModel.class));

        ContentRetriever retriever = factory.create(" ", 3, 0.21);

        assertThat(retriever).isInstanceOf(EmbeddingStoreContentRetriever.class);
        EmbeddingStoreContentRetriever embeddingRetriever = (EmbeddingStoreContentRetriever) retriever;
        assertThat(filterProvider(embeddingRetriever).apply(Query.from("anything")))
                .isEqualTo(metadataKey("rag_scope").isEqualTo("master"));
        assertThat(maxResultsProvider(embeddingRetriever).apply(Query.from("anything"))).isEqualTo(3);
        assertThat(minScoreProvider(embeddingRetriever).apply(Query.from("anything"))).isEqualTo(0.21);
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

    @SuppressWarnings("unchecked")
    private static Function<Query, Filter> filterProvider(EmbeddingStoreContentRetriever retriever) throws Exception {
        Field field = EmbeddingStoreContentRetriever.class.getDeclaredField("filterProvider");
        field.setAccessible(true);
        return (Function<Query, Filter>) field.get(retriever);
    }

    @SuppressWarnings("unchecked")
    private static Function<Query, Integer> maxResultsProvider(EmbeddingStoreContentRetriever retriever) throws Exception {
        Field field = EmbeddingStoreContentRetriever.class.getDeclaredField("maxResultsProvider");
        field.setAccessible(true);
        return (Function<Query, Integer>) field.get(retriever);
    }

    @SuppressWarnings("unchecked")
    private static Function<Query, Double> minScoreProvider(EmbeddingStoreContentRetriever retriever) throws Exception {
        Field field = EmbeddingStoreContentRetriever.class.getDeclaredField("minScoreProvider");
        field.setAccessible(true);
        return (Function<Query, Double>) field.get(retriever);
    }

    static class ScopedContentRetrieverFactoryContextTestConfig {

        @Bean
        PgVectorEmbeddingStore embeddingStore() {
            return mock(PgVectorEmbeddingStore.class);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
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
