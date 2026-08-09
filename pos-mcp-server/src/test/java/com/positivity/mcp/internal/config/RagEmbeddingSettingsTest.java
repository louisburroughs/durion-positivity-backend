package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RagEmbeddingSettings} (G5.4 #1194; single-column since V33, #1207). */
class RagEmbeddingSettingsTest {

    @Test
    @DisplayName("defaults: embedding column at dimension 1024 binds and targets the base table")
    void defaults_embedding1024() {
        RagEmbeddingSettings settings = new RagEmbeddingSettings("embedding", 1024);

        assertThat(settings.embeddingColumn()).isEqualTo("embedding");
        assertThat(settings.dimension()).isEqualTo(1024);
        assertThat(settings.vectorTableFor("mcp_document_embedding")).isEqualTo("mcp_document_embedding");
    }

    @Test
    @DisplayName("the retired embedding_1024 column name is rejected with migration guidance")
    void retiredDualColumnName_throws() {
        assertThatThrownBy(() -> new RagEmbeddingSettings("embedding_1024", 1024))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V33")
                .hasMessageContaining("MCP_RAG_EMBEDDING_COLUMN");
    }

    @Test
    @DisplayName("the retired 768 dimension is rejected with migration guidance")
    void retired768Dimension_throws() {
        assertThatThrownBy(() -> new RagEmbeddingSettings("embedding", 768))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1024")
                .hasMessageContaining("MCP_RAG_DIMENSION");
    }

    @Test
    @DisplayName("unknown column names are rejected (whitelist doubles as SQL-injection guard)")
    void unknownColumn_throws() {
        assertThatThrownBy(() -> new RagEmbeddingSettings("embedding; DROP TABLE mcp_tool", 1024))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp.rag.embedding-column");
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    void whitespace_trimmed() {
        assertThat(new RagEmbeddingSettings(" embedding ", 1024).embeddingColumn())
                .isEqualTo("embedding");
    }
}
