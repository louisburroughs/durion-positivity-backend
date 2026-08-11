package com.positivity.mcp.internal.orchestration.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.HybridRetrievalProperties;
import com.positivity.mcp.internal.domain.RagScope;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ScopedContentRetrieverFactoryTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ScopedContentRetrieverFactory.class, ScopedContentRetrieverFactoryContextTestConfig.class);

    private static ScopedContentRetrieverFactory factoryWith(
            PgVectorStore embeddingStore, JdbcTemplate jdbcTemplate, HybridRetrievalProperties hybridProperties) {
        return new ScopedContentRetrieverFactory(embeddingStore, jdbcTemplate, new ObjectMapper(), hybridProperties);
    }

    private static HybridRetrievalProperties lexicalDisabled() {
        return new HybridRetrievalProperties(false, 60, 20);
    }

    @Test
    void createsRetrieverFilteredToNormalizedRagScope() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        when(embeddingStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("stock")));
        ScopedContentRetrieverFactory factory =
                factoryWith(embeddingStore, mock(JdbcTemplate.class), lexicalDisabled());

        QueryDocumentRetriever retriever = factory.create(" INVENTORY ", 7, 0.42);
        retriever.retrieve("stock");

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(embeddingStore).similaritySearch(requestCaptor.capture());
        SearchRequest request = requestCaptor.getValue();
        assertThat(request.getQuery()).isEqualTo("stock");
        assertThat(request.getTopK()).isEqualTo(7);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.42);
        // #1180: a domain scope includes master-scope docs (glossary/capability tier) alongside
        // its own — a strict eq filter made them structurally unreachable from domain agents.
        assertThat(request.getFilterExpression())
                .isEqualTo(new FilterExpressionBuilder()
                        .in("rag_scope", "inventory", RagScope.MASTER)
                        .build());
    }

    @Test
    void masterScopeSearchesAllScopesWithoutFilter() {
        // #1124 item 4: master is the catch-all agent, not a literal doc scope. A strict
        // rag_scope='master' filter made every domain doc unreachable for general questions, so the
        // master scope must search all scopes (no rag_scope filter). Blank/null normalizes to master.
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        when(embeddingStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("anything")));
        ScopedContentRetrieverFactory factory =
                factoryWith(embeddingStore, mock(JdbcTemplate.class), lexicalDisabled());

        factory.create(" ", 3, 0.21).retrieve("anything");

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(embeddingStore).similaritySearch(requestCaptor.capture());
        SearchRequest request = requestCaptor.getValue();
        assertThat(request.getQuery()).isEqualTo("anything");
        assertThat(request.getTopK()).isEqualTo(3);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.21);
        assertThat(request.hasFilterExpression())
                .as("master must not filter by rag_scope")
                .isFalse();
    }

    @Test
    void explicitMasterScopeAlsoSearchesAllScopes() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        when(embeddingStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ScopedContentRetrieverFactory factory =
                factoryWith(embeddingStore, mock(JdbcTemplate.class), lexicalDisabled());

        factory.create("master", 5, 0.6).retrieve("what is a VIN");

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(embeddingStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().hasFilterExpression()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createLexicalMasterScopeIssuesUnscopedFtsQuery() {
        // Mirror of the dense path: lexical retrieval at master scope must drop the rag_scope clause
        // and bind only (query, query, maxResults) — no scope parameter.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new Document("hit")));
        ScopedContentRetrieverFactory factory =
                factoryWith(mock(PgVectorStore.class), jdbcTemplate, new HybridRetrievalProperties(true, 60, 7));

        factory.createLexical("master").orElseThrow().retrieve("VIN format");

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsExactly("VIN format", "VIN format", 7);
    }

    @Test
    void createLexicalIsEmptyWhenDisabled() {
        ScopedContentRetrieverFactory factory =
                factoryWith(mock(PgVectorStore.class), mock(JdbcTemplate.class), lexicalDisabled());

        assertThat(factory.createLexical("inventory")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createLexicalWhenEnabledIssuesScopedFtsQuery() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new Document("hit")));
        ScopedContentRetrieverFactory factory =
                factoryWith(mock(PgVectorStore.class), jdbcTemplate, new HybridRetrievalProperties(true, 60, 7));

        Optional<QueryDocumentRetriever> lexical = factory.createLexical(" INVENTORY ");
        assertThat(lexical).isPresent();
        lexical.orElseThrow().retrieve("stock levels");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());
        // Bound params: query (match), normalized scope, query (rank), maxResults.
        assertThat(argsCaptor.getValue()).containsExactly("stock levels", "inventory", "stock levels", 7);
        // #1124 residual: the parsed tsquery must be transformed to OR semantics (& -> |) so a
        // natural-language question that merely contains an identifier still matches.
        assertThat(sqlCaptor.getValue())
                .contains("websearch_to_tsquery")
                .contains("replace(")
                .contains("' & '", "' | '");
    }

    @Test
    void createLexicalReturnsEmptyDocumentsForBlankQueryWithoutQuerying() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ScopedContentRetrieverFactory factory =
                factoryWith(mock(PgVectorStore.class), jdbcTemplate, new HybridRetrievalProperties(true, 60, 20));

        List<Document> result = factory.createLexical("inventory").orElseThrow().retrieve("   ");

        assertThat(result).isEmpty();
        verify(jdbcTemplate, org.mockito.Mockito.never()).query(anyString(), any(RowMapper.class), any(Object[].class));
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

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        HybridRetrievalProperties hybridRetrievalProperties() {
            return new HybridRetrievalProperties(false, 60, 20);
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
