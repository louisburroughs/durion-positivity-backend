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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("alpha")
public class ToolEmbeddingInitializer implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(ToolEmbeddingInitializer.class);

  private final JdbcTemplate jdbcTemplate;
  private final EmbeddingModel embeddingModel;

  public ToolEmbeddingInitializer(
      @NonNull JdbcTemplate jdbcTemplate,
      @NonNull EmbeddingModel embeddingModel) {
    this.jdbcTemplate = jdbcTemplate;
    this.embeddingModel = embeddingModel;
  }

  @Override
  public void run(ApplicationArguments args) {
    String query = "SELECT id, description FROM mcp_tool WHERE embedding IS NULL";
    List<ToolDescriptionRow> rows = jdbcTemplate.query(
        query,
        (resultSet, rowNum) -> new ToolDescriptionRow(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("description")));

    int populated = 0;
    for (ToolDescriptionRow row : rows) {
      try {
        float[] vector = embeddingModel.embed(row.description()).content().vector();
        jdbcTemplate.update(
            "UPDATE mcp_tool SET embedding = ?::vector WHERE id = ?",
            toVectorPGobject(vector),
            row.id());
        populated++;
      } catch (Exception exception) {
        LOGGER.warn("Failed to populate embedding for tool {}", row.id(), exception);
      }
    }

    LOGGER.info("Populated embeddings for {} tools", populated);
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
      @NonNull UUID id,
      @NonNull String description) {
  }
}
