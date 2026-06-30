package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolMetadata;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
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
    public @NonNull List<ToolMetadata> findEnabledByPermissionsAndWorkflow(
            @NonNull Set<String> permissionCodes, @NonNull String workflowState) {
        if (permissionCodes.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT DISTINCT t.id, t.name, t.display_name, t.description,
                       t.domain, t.priority, t.cost_level,
                       t.avg_latency_ms, t.enabled, t.handler_bean
                FROM mcp_tool t
                JOIN mcp_tool_permission tp ON tp.tool_id = t.id
                JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
                JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
                WHERE t.enabled = true
                  AND tp.permission_code = ANY(?)
                  AND ws.name = ?
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("varchar", permissionCodes.toArray()));
                    ps.setString(2, workflowState);
                },
                this::mapRow);
    }

    // Gate 2B / #780: findAllRoleNames() and findEnabledByRoleAndWorkflow() removed — the legacy
    // mcp_role / mcp_tool_role tables are no longer queried at runtime. Candidate gating runs
    // solely through findTopKByEmbeddingForPermissions (permission codes + workflow state).

    @Override
    public @NonNull List<ToolMetadata> findEnabledByWorkflow(@NonNull String workflowState) {
        String sql = """
                SELECT t.id, t.name, t.display_name, t.description,
                       t.domain, t.priority, t.cost_level,
                       t.avg_latency_ms, t.enabled, t.handler_bean
                FROM mcp_tool t
                JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
                JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
                WHERE t.enabled = true
                  AND ws.name = ?
                ORDER BY lower(t.domain), t.name
                """;

        return jdbcTemplate.query(sql, this::mapRow, workflowState);
    }

    @Override
    public @NonNull List<ToolMetadata> findTopKByEmbeddingForPermissions(
            float @NonNull [] embedding,
            int limit,
            @NonNull Set<String> permissionCodes,
            @NonNull String workflowState) {
        if (permissionCodes.isEmpty()) {
            return List.of();
        }
        // t.id IN (SELECT ...) rather than a JOIN avoids row duplication when a tool
        // matches multiple permission codes, without DISTINCT (which would force
        // ORDER BY's <=> expression into the SELECT list).
        String sql = """
                SELECT t.id, t.name, t.display_name, t.description,
                       t.domain, t.priority, t.cost_level,
                       t.avg_latency_ms, t.enabled, t.handler_bean
                FROM mcp_tool t
                JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
                JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
                WHERE t.enabled = true
                  AND t.embedding IS NOT NULL
                  AND ws.name = ?
                  AND t.id IN (SELECT tool_id FROM mcp_tool_permission WHERE permission_code = ANY(?))
                ORDER BY t.embedding <=> ?::vector, t.id
                LIMIT ?
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, workflowState);
                    ps.setArray(2, ps.getConnection().createArrayOf("varchar", permissionCodes.toArray()));
                    ps.setObject(3, toVectorPGobject(embedding));
                    ps.setInt(4, limit);
                },
                this::mapRow);
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
