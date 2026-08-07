package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RagEmbeddingSettings} (Gate 5 G5.4, #1194). */
class RagEmbeddingSettingsTest {

    @Test
    @DisplayName("defaults: embedding column with dimension 768 binds and targets the base table")
    void defaults_embedding768() {
        RagEmbeddingSettings settings = new RagEmbeddingSettings("embedding", 768);

        assertThat(settings.embeddingColumn()).isEqualTo("embedding");
        assertThat(settings.dimension()).isEqualTo(768);
        assertThat(settings.vectorTableFor("mcp_document_embedding")).isEqualTo("mcp_document_embedding");
    }

    @Test
    @DisplayName("embedding_1024 with dimension 1024 binds and targets the _1024 view")
    void embedding1024_dimension1024() {
        RagEmbeddingSettings settings = new RagEmbeddingSettings("embedding_1024", 1024);

        assertThat(settings.embeddingColumn()).isEqualTo("embedding_1024");
        assertThat(settings.dimension()).isEqualTo(1024);
        assertThat(settings.vectorTableFor("mcp_document_embedding")).isEqualTo("mcp_document_embedding_1024");
    }

    @Test
    @DisplayName("embedding_1024 with dimension 768 fails fast with a clear message")
    void embedding1024_wrongDimension_throws() {
        assertThatThrownBy(() -> new RagEmbeddingSettings("embedding_1024", 768))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding_1024")
                .hasMessageContaining("1024");
    }

    @Test
    @DisplayName("embedding with dimension 1024 fails fast (guards against a half-flipped config)")
    void embedding_wrongDimension_throws() {
        assertThatThrownBy(() -> new RagEmbeddingSettings("embedding", 1024))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("768");
    }

    @Test
    @DisplayName("unknown column names are rejected (whitelist doubles as SQL-injection guard)")
    void unknownColumn_throws() {
        assertThatThrownBy(() -> new RagEmbeddingSettings("embedding; DROP TABLE mcp_tool", 768))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp.rag.embedding-column");
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    void whitespace_trimmed() {
        assertThat(new RagEmbeddingSettings(" embedding ", 768).embeddingColumn())
                .isEqualTo("embedding");
    }
}
