package com.positivity.mcp.internal.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Gate 3 (G3.1): runs after ToolBootstrapRunner (@Order 10) so it also backfills embeddings for
// openapi rows persisted during discovery in the same startup.
@Component
@Profile("!test")
@Order(20)
public class ToolEmbeddingInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolEmbeddingInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    public ToolEmbeddingInitializer(@NonNull JdbcTemplate jdbcTemplate, @NonNull EmbeddingModel embeddingModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        long totalStartNanos = System.nanoTime();
        String query = "SELECT id, name, description FROM mcp_tool WHERE embedding IS NULL";
        List<ToolDescriptionRow> rows = jdbcTemplate.query(
                query,
                (resultSet, rowNum) -> new ToolDescriptionRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getString("description")));

        LOGGER.info("Tool embedding initialization found {} tools missing embeddings", rows.size());
        int populated = 0;
        for (ToolDescriptionRow row : rows) {
            long rowStartNanos = System.nanoTime();
            try {
                float[] vector =
                        embeddingModel.embed(row.description()).content().vector();
                jdbcTemplate.update(
                        "UPDATE mcp_tool SET embedding = ?::vector WHERE id = ?", toVectorPGobject(vector), row.id());
                populated++;
                LOGGER.info(
                        "Populated embedding for tool {} ({}) in {} ms",
                        row.name(),
                        row.id(),
                        elapsedMs(rowStartNanos));
            } catch (Exception exception) {
                LOGGER.warn(
                        "Failed to populate embedding for tool {} ({}) after {} ms",
                        row.name(),
                        row.id(),
                        elapsedMs(rowStartNanos),
                        exception);
            }
        }

        LOGGER.info("Populated embeddings for {} tools in {} ms", populated, elapsedMs(totalStartNanos));
    }

    private static PGobject toVectorPGobject(float[] embedding) {
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                value.append(",");
            }
            value.append(embedding[i]);
        }
        value.append("]");

        PGobject object = new PGobject();
        object.setType("vector");
        try {
            object.setValue(value.toString());
        } catch (SQLException exception) {
            throw new IllegalArgumentException("Failed to build vector PGobject", exception);
        }
        return object;
    }

    private record ToolDescriptionRow(
            @NonNull UUID id, @NonNull String name, @NonNull String description) {}

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
