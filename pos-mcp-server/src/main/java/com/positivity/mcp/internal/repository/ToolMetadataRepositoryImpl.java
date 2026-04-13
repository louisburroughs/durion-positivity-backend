package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolMetadata;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class ToolMetadataRepositoryImpl implements ToolMetadataRepository {

  private final JdbcTemplate jdbcTemplate;

  public ToolMetadataRepositoryImpl(@NonNull JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public @NonNull List<ToolMetadata> findEnabledByRoleAndWorkflow(
      @NonNull String role,
      @NonNull String workflowState) {
    String sql = """
        SELECT t.id, t.name, t.display_name, t.description,
               t.domain, t.priority, t.cost_level,
               t.avg_latency_ms, t.enabled, t.handler_bean
        FROM mcp_tool t
        JOIN mcp_tool_role tr ON t.id = tr.tool_id
        JOIN mcp_role r ON tr.role_id = r.id
        JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
        JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
        WHERE t.enabled = true
          AND r.name = ?
          AND ws.name = ?
        """;

    return jdbcTemplate.query(sql, this::mapRow, role, workflowState);
  }

  @Override
  public @NonNull List<ToolMetadata> findTopKByEmbedding(float @NonNull [] embedding, int limit) {
    String sql = """
        SELECT id, name, display_name, description,
               domain, priority, cost_level,
               avg_latency_ms, enabled, handler_bean
        FROM mcp_tool
        WHERE enabled = true
          AND embedding IS NOT NULL
        ORDER BY embedding <=> ?::vector, id
        LIMIT ?
        """;

    return jdbcTemplate.query(sql, this::mapRow, toVectorPGobject(embedding), limit);
  }

  private static PGobject toVectorPGobject(float[] embedding) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(embedding[i]);
    }
    sb.append("]");
    PGobject pgVector = new PGobject();
    pgVector.setType("vector");
    try {
      pgVector.setValue(sb.toString());
    } catch (SQLException e) {
      throw new IllegalArgumentException("Failed to create vector PGobject", e);
    }
    return pgVector;
  }

  private ToolMetadata mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new ToolMetadata(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("display_name"),
        rs.getString("description"),
        rs.getString("domain"),
        rs.getDouble("priority"),
        rs.getString("cost_level"),
        rs.getInt("avg_latency_ms"),
        rs.getBoolean("enabled"),
        rs.getString("handler_bean"));
  }
}
