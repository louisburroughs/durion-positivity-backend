package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolPrerequisite;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate-backed prerequisite lookup over {@code mcp_tool_prerequisite}. */
@Repository
@Profile({"!test", "openapi"})
public class ToolPrerequisiteRepositoryImpl implements ToolPrerequisiteRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolPrerequisiteRepositoryImpl(@NonNull JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public @NonNull List<ToolPrerequisite> findByToolName(@NonNull String toolName) {
        return jdbcTemplate.query(
                """
                SELECT tool_name, required_param, producing_tool, producing_field
                FROM mcp_tool_prerequisite
                WHERE tool_name = ?
                """,
                (rs, rowNum) -> new ToolPrerequisite(
                        rs.getString("tool_name"),
                        rs.getString("required_param"),
                        rs.getString("producing_tool"),
                        rs.getString("producing_field")),
                toolName);
    }
}
