package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolInvocationLog;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class ToolAuditRepositoryImpl implements ToolAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolAuditRepositoryImpl(@NonNull JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void logInvocation(@NonNull ToolInvocationLog log) {
        String sql = """
        INSERT INTO mcp_tool_invocation_log (
          tool_id,
          user_id,
          session_id,
          intent,
          workflow_state,
          semantic_rank,
          final_score,
          selected,
          success,
          fallback_invoked,
          execution_time_ms,
          error_type
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        jdbcTemplate.update(
                sql,
                log.toolId(),
                log.userId(),
                log.sessionId(),
                log.intent(),
                log.workflowState(),
                log.semanticRank(),
                log.finalScore(),
                log.selected(),
                log.success(),
                log.fallbackInvoked(),
                log.executionTimeMs(),
                log.errorType());
    }
}
