package com.positivity.mcp.internal.orchestration.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class LexicalDocumentRetrieverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("blank query short-circuits to empty without touching the database")
    void blankQuery_returnsEmptyWithoutQuery() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LexicalDocumentRetriever retriever = new LexicalDocumentRetriever(jdbcTemplate, objectMapper, "inventory", 20);

        assertThat(retriever.retrieve("   ")).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("maps a row to a Document with the DB id, content, and parsed metadata")
    @SuppressWarnings("unchecked")
    void mapsRowToDocumentWithIdAndMetadata() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("doc-1");
        when(rs.getString("content")).thenReturn("Inventory stock levels");
        when(rs.getString("metadata")).thenReturn("{\"rag_scope\":\"inventory\",\"title\":\"Stock\"}");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Document> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(rs, 0));
                });

        LexicalDocumentRetriever retriever = new LexicalDocumentRetriever(jdbcTemplate, objectMapper, "inventory", 20);
        List<Document> docs = retriever.retrieve("stock");

        assertThat(docs).hasSize(1);
        Document doc = docs.get(0);
        assertThat(doc.getId()).isEqualTo("doc-1");
        assertThat(doc.getText()).isEqualTo("Inventory stock levels");
        assertThat(doc.getMetadata()).containsEntry("rag_scope", "inventory").containsEntry("title", "Stock");
    }

    @Test
    @DisplayName("null/absent metadata falls back to minimal scope metadata")
    @SuppressWarnings("unchecked")
    void nullMetadataFallsBackToScopeOnly() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("doc-2");
        when(rs.getString("content")).thenReturn("body");
        when(rs.getString("metadata")).thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Document> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(rs, 0));
                });

        LexicalDocumentRetriever retriever =
                new LexicalDocumentRetriever(jdbcTemplate, objectMapper, " INVENTORY ", 20);
        Document doc = retriever.retrieve("body").get(0);

        assertThat(doc.getMetadata()).containsEntry("rag_scope", "inventory");
    }
}
