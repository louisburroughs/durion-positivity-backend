package com.positivity.mcp.internal.config;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Backfills {@code mcp_screen_registry.embedding} for rows seeded without one (rung 3 of the answer
 * resolution ladder). Mirrors {@link ToolEmbeddingInitializer}. Fail-soft — a per-row embedding
 * failure is logged and skipped so startup never blocks.
 */
@Component
@Profile("!test")
@Order(21)
public class ScreenEmbeddingInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenEmbeddingInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    /** Validated vector column (Gate 5 G5.4, #1194): {@code embedding} or {@code embedding_1024}. */
    private final String embeddingColumn;

    public ScreenEmbeddingInitializer(
            @NonNull JdbcTemplate jdbcTemplate,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull RagEmbeddingSettings embeddingSettings) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.embeddingColumn = embeddingSettings.embeddingColumn();
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ScreenDescriptionRow> rows = jdbcTemplate.query(
                "SELECT id, screen_key, description FROM mcp_screen_registry WHERE %s IS NULL"
                        .formatted(embeddingColumn),
                (resultSet, rowNum) -> new ScreenDescriptionRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("screen_key"),
                        resultSet.getString("description")));

        LOGGER.info("Screen embedding initialization found {} screens missing embeddings", rows.size());
        int populated = 0;
        for (ScreenDescriptionRow row : rows) {
            try {
                float[] vector = embeddingModel.embed(row.description());
                jdbcTemplate.update(
                        "UPDATE mcp_screen_registry SET %s = ?::vector WHERE id = ?".formatted(embeddingColumn),
                        toVectorPGobject(vector),
                        row.id());
                populated++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to populate embedding for screen {} ({})", row.screenKey(), row.id(), exception);
            }
        }
        LOGGER.info("Populated embeddings for {} screens", populated);
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
        } catch (SQLException exception) {
            throw new IllegalArgumentException("Failed to build vector PGobject", exception);
        }
        return object;
    }

    private record ScreenDescriptionRow(
            @NonNull UUID id,
            @NonNull String screenKey,
            @NonNull String description) {}
}
