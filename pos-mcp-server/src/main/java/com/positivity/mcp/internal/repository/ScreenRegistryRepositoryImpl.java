package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ScreenCandidate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * pgvector-backed screen lookup. Mirrors {@link ToolMetadataRepositoryImpl}'s cosine-distance
 * search (the {@code <=>} operator); {@code 1 - distance} is projected as the similarity score.
 */
@Repository
@Profile({"!test", "openapi"})
public class ScreenRegistryRepositoryImpl implements ScreenRegistryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ScreenRegistryRepositoryImpl(@NonNull JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public @NonNull List<ScreenCandidate> findNearest(
            float @NonNull [] queryEmbedding, @Nullable String domain, int limit) {
        // The vector column is the single validated value from RagEmbeddingSettings (V33, #1207),
        // written literally so both statement variants are constants (java:S2077).
        String sql = domain != null ? """
                SELECT screen_key, title, url_template, domain, required_perm,
                       1 - (embedding <=> ?::vector) AS score
                FROM mcp_screen_registry
                WHERE embedding IS NOT NULL
                  AND domain = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """ : """
                SELECT screen_key, title, url_template, domain, required_perm,
                       1 - (embedding <=> ?::vector) AS score
                FROM mcp_screen_registry
                WHERE embedding IS NOT NULL
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;

        PGobject vector = toVectorPGobject(queryEmbedding);
        return jdbcTemplate.query(
                sql,
                ps -> {
                    int i = 1;
                    ps.setObject(i++, vector);
                    if (domain != null) {
                        ps.setString(i++, domain);
                    }
                    ps.setObject(i++, vector);
                    ps.setInt(i, limit);
                },
                (rs, rowNum) -> new ScreenCandidate(
                        rs.getString("screen_key"),
                        rs.getString("title"),
                        rs.getString("url_template"),
                        rs.getString("domain"),
                        rs.getString("required_perm"),
                        rs.getDouble("score")));
    }

    private static PGobject toVectorPGobject(float[] embedding) {
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                value.append(',');
            }
            value.append(embedding[i]);
        }
        value.append(']');
        PGobject object = new PGobject();
        object.setType("vector");
        try {
            object.setValue(value.toString());
        } catch (java.sql.SQLException exception) {
            throw new IllegalArgumentException("Failed to build vector PGobject", exception);
        }
        return object;
    }
}
