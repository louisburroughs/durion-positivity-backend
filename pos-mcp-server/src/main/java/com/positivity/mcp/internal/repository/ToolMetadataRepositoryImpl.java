package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.domain.ToolMetadata;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC tool-metadata store. The pgvector column referenced by the vector-search statements is the
 * single validated value from {@code RagEmbeddingSettings} (V33, #1207 — always {@code embedding}),
 * written literally so every statement stays a constant (java:S2077).
 */
@Repository
@Profile({"!test", "openapi"})
public class ToolMetadataRepositoryImpl implements ToolMetadataRepository {
    private static final String VARCHAR = "varchar";

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
        // V40 / #1606: AND-group gating. A facade qualifies iff the caller holds EVERY code of at
        // least one permission_group (a group is one @Tool method's required codes). bool_and over
        // a group is only reached when the group has rows, and EXISTS over no rows is false — so a
        // tool with zero mcp_tool_permission rows is still never returned (fail-closed).
        String sql = """
                SELECT t.id, t.name, t.display_name, t.description,
                       t.domain, t.priority, t.cost_level,
                       t.avg_latency_ms, t.enabled, t.handler_bean
                FROM mcp_tool t
                JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
                JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
                WHERE t.enabled = true
                  AND t.source <> 'openapi'
                  AND ws.name = ?
                  AND EXISTS (SELECT 1 FROM mcp_tool_permission g
                              WHERE g.tool_id = t.id
                              GROUP BY g.permission_group
                              HAVING bool_and(g.permission_code = ANY(?)))
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, workflowState);
                    ps.setArray(2, ps.getConnection().createArrayOf(VARCHAR, permissionCodes.toArray()));
                },
                this::mapRow);
    }

    // Gate 2B / #780: findAllRoleNames() and findEnabledByRoleAndWorkflow() removed
    // — the legacy
    // mcp_role / mcp_tool_role tables are no longer queried at runtime. Candidate
    // gating runs
    // solely through findTopKByEmbeddingForPermissions (permission codes + workflow
    // state).

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
                  AND t.source <> 'openapi'
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
        // EXISTS (...) rather than a JOIN avoids row duplication when a tool matches multiple
        // permission codes, without DISTINCT (which would force ORDER BY's <=> expression into
        // the SELECT list). V40 / #1606: AND-group gating — the caller must hold EVERY code of at
        // least one permission_group. EXISTS over no rows is false, so a tool with zero
        // mcp_tool_permission rows is still never returned (fail-closed).
        String sql = """
                SELECT t.id, t.name, t.display_name, t.description,
                       t.domain, t.priority, t.cost_level,
                       t.avg_latency_ms, t.enabled, t.handler_bean
                FROM mcp_tool t
                JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
                JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
                WHERE t.enabled = true
                  AND t.source <> 'openapi'
                  AND t.embedding IS NOT NULL
                  AND ws.name = ?
                  AND EXISTS (SELECT 1 FROM mcp_tool_permission g
                              WHERE g.tool_id = t.id
                              GROUP BY g.permission_group
                              HAVING bool_and(g.permission_code = ANY(?)))
                ORDER BY t.embedding <=> ?::vector, t.id
                LIMIT ?
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, workflowState);
                    ps.setArray(2, ps.getConnection().createArrayOf(VARCHAR, permissionCodes.toArray()));
                    ps.setObject(3, toVectorPGobject(embedding));
                    ps.setInt(4, limit);
                },
                this::mapRow);
    }

    @Override
    public @NonNull List<DiscoveredOperation> findDiscoveredCandidatesForPermissions(
            float @NonNull [] embedding,
            int limit,
            @NonNull Set<String> permissionCodes,
            @NonNull String workflowState) {
        if (permissionCodes.isEmpty()) {
            return List.of();
        }
        // Discovered (source='openapi') operations deliberately keep OR semantics: the meaning of
        // their x-required-permissions has not been analysed, and the V40 / #1606 AND-group
        // tightening is scoped to facade tools. That is enforced by the DATA, not a second query
        // shape — every discovered-op row carries permission_group = permission_code (V40 backfill
        // + addToolPermission below), so "all codes of some group" collapses to "any one code".
        String sql = """
                SELECT t.name, t.description, t.http_method, t.http_path, t.service_id, t.input_schema
                FROM mcp_tool t
                JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
                JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
                WHERE t.enabled = true
                  AND t.embedding IS NOT NULL
                  AND t.source = 'openapi'
                  AND ws.name = ?
                  AND t.id IN (SELECT tool_id FROM mcp_tool_permission WHERE permission_code = ANY(?))
                ORDER BY t.embedding <=> ?::vector, t.id
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, workflowState);
                    ps.setArray(2, ps.getConnection().createArrayOf(VARCHAR, permissionCodes.toArray()));
                    ps.setObject(3, toVectorPGobject(embedding));
                    ps.setInt(4, limit);
                },
                this::mapDiscovered);
    }

    @Override
    public @NonNull UUID upsertDiscoveredOperation(@NonNull DiscoveredOperation operation, @NonNull String domain) {
        // Embedding is intentionally NOT written here: it is owned by
        // ToolEmbeddingInitializer, which
        // backfills rows WHERE embedding IS NULL. ON CONFLICT preserves any existing
        // embedding so
        // re-discovery on restart does not clear it (and does not re-embed ~hundreds of
        // ops each boot).
        String sql = """
                INSERT INTO mcp_tool (name, display_name, description, domain, source,
                                      http_method, http_path, service_id, input_schema, enabled)
                VALUES (?, ?, ?, ?, 'openapi', ?, ?, ?, ?, true)
                ON CONFLICT (name) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    description  = EXCLUDED.description,
                    domain       = EXCLUDED.domain,
                    source       = 'openapi',
                    http_method  = EXCLUDED.http_method,
                    http_path    = EXCLUDED.http_path,
                    service_id   = EXCLUDED.service_id,
                    input_schema = EXCLUDED.input_schema,
                    enabled      = true
                RETURNING id
                """;
        UUID id = jdbcTemplate.queryForObject(
                sql,
                UUID.class,
                operation.name(),
                operation.name(),
                operation.description(),
                domain,
                operation.httpMethod(),
                operation.httpPath(),
                operation.serviceId(),
                operation.inputSchema());
        return Objects.requireNonNull(id, "upsertDiscoveredOperation returned no id");
    }

    @Override
    @Transactional
    public int pruneDiscoveredOperationsExcept(
            @NonNull Collection<String> keptNames, @NonNull Set<String> excludedDomains) {
        // Safety: never prune against an empty keep-set — that would delete the entire discovered
        // catalog. The caller only invokes this after a successful, non-empty discovery run.
        if (keptNames.isEmpty()) {
            return 0;
        }
        // Array binds (name <> ALL(?), tool_id = ANY(?)) keep every statement a constant string —
        // no placeholder-list concatenation (java:S2077) — matching the ANY(?) style used by the
        // permission-filtered queries above.
        Object[] keptArray = keptNames.toArray();
        List<UUID> orphanIds;
        if (excludedDomains.isEmpty()) {
            orphanIds = jdbcTemplate.query(
                    "SELECT id FROM mcp_tool WHERE source = 'openapi' AND name <> ALL(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf(VARCHAR, keptArray)),
                    (rs, rowNum) -> rs.getObject("id", UUID.class));
        } else {
            // #1632: rows in an excluded domain survive even when absent from keptNames — their
            // service's spec fetch failed this cycle, so absence means "unseen", not "removed".
            // A NULL domain is by definition not in excludedDomains and stays prunable.
            Object[] excludedArray = excludedDomains.toArray();
            orphanIds = jdbcTemplate.query(
                    "SELECT id FROM mcp_tool WHERE source = 'openapi' AND name <> ALL(?) "
                            + "AND (domain IS NULL OR domain <> ALL(?))",
                    ps -> {
                        ps.setArray(1, ps.getConnection().createArrayOf(VARCHAR, keptArray));
                        ps.setArray(2, ps.getConnection().createArrayOf(VARCHAR, excludedArray));
                    },
                    (rs, rowNum) -> rs.getObject("id", UUID.class));
        }
        if (orphanIds.isEmpty()) {
            return 0;
        }
        Object[] idArray = orphanIds.toArray();
        // Clear FK children explicitly (portable across Postgres and the H2 test schema, which may not
        // declare ON DELETE CASCADE): permission grants and workflow links are deleted; the nullable
        // invocation log's tool_id is nulled so historical audit rows survive the tool's removal.
        jdbcTemplate.update(
                "DELETE FROM mcp_tool_permission WHERE tool_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", idArray)));
        jdbcTemplate.update(
                "DELETE FROM mcp_tool_workflow WHERE tool_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", idArray)));
        jdbcTemplate.update(
                "UPDATE mcp_tool_invocation_log SET tool_id = NULL WHERE tool_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", idArray)));
        return jdbcTemplate.update(
                "DELETE FROM mcp_tool WHERE id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", idArray)));
    }

    @Override
    public void linkToolToWorkflow(@NonNull UUID toolId, @NonNull String workflowState) {
        jdbcTemplate.update("""
                INSERT INTO mcp_tool_workflow (tool_id, workflow_state_id)
                SELECT ?, id FROM mcp_workflow_state WHERE name = ?
                ON CONFLICT DO NOTHING
                """, toolId, workflowState);
    }

    @Override
    public boolean addToolPermission(@NonNull UUID toolId, @NonNull String permissionCode) {
        // V40 / #1606: the grant forms its OWN permission_group (group = code), which is the
        // OR-equivalent shape — a singleton group is satisfied by holding that one code. Multi-code
        // AND-groups are seed-derived (one per @Tool method) and are not expressible through this
        // single-code admin/discovery API.
        return jdbcTemplate.update("""
                INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, toolId, permissionCode, permissionCode) > 0;
    }

    @Override
    public @NonNull Optional<DiscoveredOperation> findDiscoveredOperationByName(@NonNull String name) {
        List<DiscoveredOperation> operations = jdbcTemplate.query("""
                SELECT name, description, http_method, http_path, service_id, input_schema
                FROM mcp_tool
                WHERE name = ? AND source = 'openapi' AND enabled = true
                """, this::mapDiscovered, name);
        return operations.isEmpty() ? Optional.empty() : Optional.of(operations.get(0));
    }

    @Override
    public @NonNull Optional<UUID> findToolIdByName(@NonNull String name) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM mcp_tool WHERE name = ?", (rs, rowNum) -> rs.getObject("id", UUID.class), name);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    @Override
    public @NonNull Optional<UUID> findDiscoveredToolIdByName(@NonNull String name) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM mcp_tool WHERE name = ? AND source = 'openapi'",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                name);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    @Override
    public @NonNull List<String> listToolPermissions(@NonNull UUID toolId) {
        return jdbcTemplate.queryForList(
                // DISTINCT: since V40 a code may appear in several permission_groups (e.g.
                // location:read in both TaxFacadeTool.calculateTax and .getTaxRate); the admin
                // view lists the codes a tool references, once each.
                "SELECT DISTINCT permission_code FROM mcp_tool_permission WHERE tool_id = ? ORDER BY permission_code",
                String.class,
                toolId);
    }

    @Override
    public boolean removeToolPermission(@NonNull UUID toolId, @NonNull String permissionCode) {
        return jdbcTemplate.update(
                        "DELETE FROM mcp_tool_permission WHERE tool_id = ? AND permission_code = ?",
                        toolId,
                        permissionCode)
                > 0;
    }

    private DiscoveredOperation mapDiscovered(ResultSet rs, int rowNum) throws SQLException {
        return new DiscoveredOperation(
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("http_method"),
                rs.getString("http_path"),
                rs.getString("service_id"),
                rs.getString("input_schema"),
                // Execution reconstruction: permissions live in mcp_tool_permission, not on the row.
                java.util.List.of());
    }

    @Override
    public @NonNull List<ToolMetadata> findTopKByEmbedding(float @NonNull [] embedding, int limit) {
        String sql = """
                SELECT id, name, display_name, description,
                       domain, priority, cost_level,
                       avg_latency_ms, enabled, handler_bean
                FROM mcp_tool
                WHERE enabled = true
                  AND source <> 'openapi'
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
