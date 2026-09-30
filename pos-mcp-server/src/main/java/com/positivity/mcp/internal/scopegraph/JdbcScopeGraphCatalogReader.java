package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ScreenRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPermissionRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPrerequisiteRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolWorkflowRow;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JdbcTemplate-backed catalog read for the scope graph, in the style of {@code
 * ToolMetadataRepositoryImpl} and conditioned like it.
 *
 * <p>Tenancy (ADR-0062 §5): every table read here ({@code mcp_tool}, {@code mcp_tool_permission},
 * {@code mcp_tool_workflow}, {@code mcp_workflow_state}, {@code mcp_tool_prerequisite}, {@code
 * mcp_screen_registry}) is listed in {@code db/tenancy-global-tables.txt}: no {@code tenant_id}, no
 * row-level-security policy. The build runs with no tenant bound, like tool bootstrap, and reads the
 * same rows whichever tenant is or is not bound. The tenant-scoped {@code mcp_tool_priority} overlay
 * is deliberately not read: the graph describes the platform, not a tenant (ADR-0069, Constraints).
 *
 * <p>The statements are constants and the reads are one transaction, so the five passes see one
 * consistent catalog even while discovery is upserting.
 */
@Component
@Profile({"!test", "openapi"})
public class JdbcScopeGraphCatalogReader implements ScopeGraphCatalogReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcScopeGraphCatalogReader(@NonNull JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ScopeGraphCatalog read() {
        return new ScopeGraphCatalog(
                jdbcTemplate.query(
                        """
                        SELECT name, domain, source, http_method, enabled
                        FROM mcp_tool
                        ORDER BY name
                        """,
                        (rs, rowNum) -> new ToolRow(
                                rs.getString("name"),
                                rs.getString("domain"),
                                rs.getString("source"),
                                rs.getString("http_method"),
                                rs.getBoolean("enabled"))),
                jdbcTemplate.query(
                        """
                        SELECT t.name AS tool_name, p.permission_code, p.permission_group
                        FROM mcp_tool_permission p
                        JOIN mcp_tool t ON t.id = p.tool_id
                        ORDER BY t.name, p.permission_group, p.permission_code
                        """,
                        (rs, rowNum) -> new ToolPermissionRow(
                                rs.getString("tool_name"),
                                rs.getString("permission_code"),
                                rs.getString("permission_group"))),
                jdbcTemplate.query(
                        """
                        SELECT t.name AS tool_name, ws.name AS workflow_state
                        FROM mcp_tool_workflow tw
                        JOIN mcp_tool t ON t.id = tw.tool_id
                        JOIN mcp_workflow_state ws ON ws.id = tw.workflow_state_id
                        ORDER BY t.name, ws.name
                        """,
                        (rs, rowNum) -> new ToolWorkflowRow(rs.getString("tool_name"), rs.getString("workflow_state"))),
                jdbcTemplate.query(
                        """
                        SELECT tool_name, required_param, producing_tool
                        FROM mcp_tool_prerequisite
                        ORDER BY tool_name, required_param, producing_tool
                        """,
                        (rs, rowNum) -> new ToolPrerequisiteRow(
                                rs.getString("tool_name"),
                                rs.getString("required_param"),
                                rs.getString("producing_tool"))),
                jdbcTemplate.query(
                        """
                        SELECT screen_key, domain, required_perm, url_template, title
                        FROM mcp_screen_registry
                        ORDER BY screen_key
                        """,
                        (rs, rowNum) -> new ScreenRow(
                                rs.getString("screen_key"),
                                rs.getString("domain"),
                                rs.getString("required_perm"),
                                rs.getString("url_template"),
                                rs.getString("title"))));
    }
}
