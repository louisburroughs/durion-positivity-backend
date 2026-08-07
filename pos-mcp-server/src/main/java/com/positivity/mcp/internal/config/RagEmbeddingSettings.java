package com.positivity.mcp.internal.config;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Embedding column/dimension selection for the bge-m3 dual-column migration (Gate 5 G5.4, #1194).
 *
 * <p>V31 added a nullable {@code embedding_1024 vector(1024)} column next to every existing
 * {@code embedding vector(768)} column. {@code mcp.rag.embedding-column} selects which column all
 * pgvector reads/writes use; the default stays {@code embedding} (768) so the unpopulated 1024
 * column can never be read by accident. Flipping to {@code embedding_1024} requires
 * {@code mcp.rag.dimension=1024} — any mismatch fails startup with a clear message.
 *
 * <p>The two-value whitelist doubles as the SQL-injection guard for the repositories that
 * interpolate the column name into their vector-search statements.
 */
@Component
public class RagEmbeddingSettings {

    /** Legacy/default 768-dim column (nomic-embed-text). */
    public static final String COLUMN_768 = "embedding";

    /** bge-m3 1024-dim column added by V31; unpopulated until the alpha re-embed. */
    public static final String COLUMN_1024 = "embedding_1024";

    private static final int DIMENSION_768 = 768;
    private static final int DIMENSION_1024 = 1024;

    private final String embeddingColumn;
    private final int dimension;

    public RagEmbeddingSettings(
            @Value("${mcp.rag.embedding-column:embedding}") @NonNull String embeddingColumn,
            @Value("${mcp.rag.dimension:768}") int dimension) {
        String normalized = embeddingColumn.trim();
        if (!COLUMN_768.equals(normalized) && !COLUMN_1024.equals(normalized)) {
            throw new IllegalStateException("Invalid mcp.rag.embedding-column '" + embeddingColumn + "'; expected '"
                    + COLUMN_768 + "' or '" + COLUMN_1024 + "'");
        }
        if (COLUMN_1024.equals(normalized) && dimension != DIMENSION_1024) {
            throw new IllegalStateException("mcp.rag.embedding-column=" + COLUMN_1024 + " requires mcp.rag.dimension="
                    + DIMENSION_1024 + " (got " + dimension + "). Set both together when cutting over to bge-m3.");
        }
        if (COLUMN_768.equals(normalized) && dimension != DIMENSION_768) {
            throw new IllegalStateException("mcp.rag.embedding-column=" + COLUMN_768 + " requires mcp.rag.dimension="
                    + DIMENSION_768 + " (got " + dimension + "). Use embedding_1024 for 1024-dim models.");
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
     * Table (or updatable view) the Spring AI {@code PgVectorStore} should target. PgVectorStore
     * hardcodes the column name {@code embedding} (see V24), so the 1024 path goes through the
     * {@code <baseTable>_1024} view created by V31, which aliases {@code embedding_1024} as
     * {@code embedding} for both reads and {@code ON CONFLICT} upserts.
     */
    public @NonNull String vectorTableFor(@NonNull String baseTable) {
        return COLUMN_1024.equals(embeddingColumn) ? baseTable + "_1024" : baseTable;
    }
}
