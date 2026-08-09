package com.positivity.mcp.internal.config;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Embedding column/dimension validation for the pgvector stores.
 *
 * <p>History (Gate 5 G5.4): #1194 migrated the corpus from nomic-embed-text 768-dim vectors to
 * bge-m3 1024-dim via a reversible dual-column path (V31 added {@code embedding_1024} + a view;
 * this class selected between the columns). After live sign-off, #1207 (V33) dropped the 768
 * columns and renamed {@code embedding_1024} back to {@code embedding} — so the only valid
 * configuration is now {@code embedding} at dimension 1024, and the view indirection is gone
 * ({@code PgVectorStore} reads the base table directly).
 *
 * <p>The single-value whitelist doubles as the SQL-injection guard for the repositories that
 * interpolate the column name into their vector-search statements. The class is retained (rather
 * than inlining constants) so a future embedding-model migration can reintroduce a dual-column
 * flip without re-plumbing the injection sites.
 */
@Component
public class RagEmbeddingSettings {

    public static final String COLUMN = "embedding";

    private static final int DIMENSION = 1024;

    private final String embeddingColumn;
    private final int dimension;

    public RagEmbeddingSettings(
            @Value("${mcp.rag.embedding-column:embedding}") @NonNull String embeddingColumn,
            @Value("${mcp.rag.dimension:1024}") int dimension) {
        String normalized = embeddingColumn.trim();
        if (!COLUMN.equals(normalized)) {
            throw new IllegalStateException("Invalid mcp.rag.embedding-column '" + embeddingColumn
                    + "'; only '" + COLUMN + "' exists since V33 (#1207) dropped the 768 columns and renamed"
                    + " embedding_1024 to embedding. Remove any MCP_RAG_EMBEDDING_COLUMN override.");
        }
        if (dimension != DIMENSION) {
            throw new IllegalStateException("mcp.rag.dimension must be " + DIMENSION + " (bge-m3) — got " + dimension
                    + ". The 768-dim nomic path was retired by V33 (#1207); remove any MCP_RAG_DIMENSION override.");
        }
        this.embeddingColumn = normalized;
        this.dimension = dimension;
    }

    /** Validated vector column name, safe to interpolate into SQL. */
    public @NonNull String embeddingColumn() {
        return embeddingColumn;
    }

    public int dimension() {
        return dimension;
    }

    /**
     * Table the Spring AI {@code PgVectorStore} should target. Since V33 the base table's
     * {@code embedding} column is 1024-dim, so no view indirection is needed.
     */
    public @NonNull String vectorTableFor(@NonNull String baseTable) {
        return baseTable;
    }
}
