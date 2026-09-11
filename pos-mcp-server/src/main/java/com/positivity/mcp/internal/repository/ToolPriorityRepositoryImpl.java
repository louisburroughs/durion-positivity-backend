package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolInvocationStats;
import com.positivity.mcp.internal.domain.ToolPriorityOverlay;
import com.positivity.tenancy.TenantAudited;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link ToolPriorityRepository}. The overlay and the invocation log are
 * tenant-scoped tables read through the bound connection: no statement names a tenant, row-level
 * security supplies it (ADR-0062 §5). {@code mcp_tool} is global.
 */
@Repository
@TenantAudited(
        reason = "mcp_tool_priority and mcp_tool_invocation_log are read and written through the bound"
                + " connection only; every statement leaves tenant_id to row-level security and the column"
                + " default, and mcp_tool is a global catalog table")
public class ToolPriorityRepositoryImpl implements ToolPriorityRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolPriorityRepositoryImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ToolPriorityRepositoryImpl(@NonNull JdbcTemplate jdbcTemplate, @NonNull Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public @NonNull Map<UUID, ToolPriorityOverlay> findOverlayForCurrentTenant() {
        List<ToolPriorityOverlay> rows = jdbcTemplate.query(
                "SELECT tool_id, priority, avg_latency_ms FROM mcp_tool_priority",
                ToolPriorityRepositoryImpl::mapOverlay);
        Map<UUID, ToolPriorityOverlay> byTool = new HashMap<>();
        for (ToolPriorityOverlay row : rows) {
            byTool.put(row.toolId(), row);
        }
        return byTool;
    }

    @Override
    public void upsertOverlay(@NonNull UUID toolId, double priority, int avgLatencyMs) {
        // Update-then-insert rather than a single native upsert: the insert names no tenant_id so
        // the column default (app_current_tenant() on Postgres) stamps the bound tenant, and no
        // portable single statement covers both chains this module runs on. Postgres supports
        // INSERT ... ON CONFLICT DO UPDATE, but the H2 dev/test chain runs in PostgreSQL
        // compatibility mode where H2's parser only ever accepts the ON CONFLICT DO NOTHING form
        // (org.h2.command.Parser#parseInsertCompatibility, h2database 2.4.240) — DO UPDATE is a
        // syntax error there — so ON CONFLICT is not an option across both chains.
        //
        // Two instances tuning the same tenant at once can both see no row and race on the insert;
        // the loser's primary-key violation is caught and its values applied with a second update.
        // That retry alone is not enough now that ToolPriorityTuningService runs a whole tenant's
        // sweep in one transaction (JdbcTemplate no longer commits each statement on its own): on
        // PostgreSQL a failed statement aborts the enclosing transaction until the next rollback
        // ("current transaction is aborted, commands ignored until end of transaction block"), so a
        // bare retry UPDATE issued straight after the failed INSERT would itself be refused, taking
        // down the rest of this tenant's sweep and forcing the global rollup to skip. A JDBC
        // savepoint taken immediately before the INSERT confines that abort: rolling back to it
        // undoes only the failed insert attempt, leaving the rest of the transaction — and
        // everything this tenant's sweep already wrote in it — intact for the retry update.
        if (updateOverlay(toolId, priority, avgLatencyMs) > 0) {
            return;
        }
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "JdbcTemplate has no DataSource");
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            Savepoint savepoint;
            try {
                savepoint = connection.setSavepoint("mcpToolPriorityUpsert");
            } catch (SQLException settingSavepointFailed) {
                throw new UncategorizedSQLException("setSavepoint", null, settingSavepointFailed);
            }
            try {
                jdbcTemplate.update(
                        "INSERT INTO mcp_tool_priority (tool_id, priority, avg_latency_ms) VALUES (?, ?, ?)",
                        toolId,
                        priority,
                        avgLatencyMs);
            } catch (DuplicateKeyException raced) {
                LOGGER.debug(
                        "Overlay row for tool {} was inserted concurrently; rolling back to the pre-insert"
                                + " savepoint and applying this run's values by update",
                        toolId);
                try {
                    connection.rollback(savepoint);
                } catch (SQLException rollbackFailed) {
                    throw new UncategorizedSQLException("rollback to savepoint", null, rollbackFailed);
                }
                if (updateOverlay(toolId, priority, avgLatencyMs) == 0) {
                    throw new IllegalStateException(
                            "Overlay row for tool " + toolId + " vanished between a duplicate insert and its update",
                            raced);
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private int updateOverlay(@NonNull UUID toolId, double priority, int avgLatencyMs) {
        return jdbcTemplate.update(
                "UPDATE mcp_tool_priority SET priority = ?, avg_latency_ms = ?, updated_at = ? WHERE tool_id = ?",
                priority,
                avgLatencyMs,
                Instant.now(clock).atOffset(ZoneOffset.UTC),
                toolId);
    }

    @Override
    public @NonNull List<ToolInvocationStats> invocationStatsSince(@NonNull Instant cutoff) {
        String sql = """
                SELECT tool_id,
                       COUNT(*) AS total_calls,
                       SUM(CASE WHEN success THEN 1 ELSE 0 END) AS success_count,
                       SUM(execution_time_ms) AS latency_sum_ms,
                       SUM(CASE WHEN fallback_invoked THEN 1 ELSE 0 END) AS fallback_count
                FROM mcp_tool_invocation_log
                WHERE created_at > ?
                  AND tool_id IS NOT NULL
                  AND execution_time_ms >= 0
                GROUP BY tool_id
                """;
        // created_at is timestamp without time zone: bind the instant at UTC, as the log writes it.
        return jdbcTemplate.query(
                sql,
                ToolPriorityRepositoryImpl::mapStats,
                cutoff.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    @Override
    public @NonNull Map<UUID, Double> findGlobalPriorities() {
        // One scan of the global catalog for the whole tuning run. getDouble reads a SQL NULL
        // priority as 0.0, which is what the per-tool lookup this replaced also returned.
        List<GlobalPriority> rows = jdbcTemplate.query(
                "SELECT id, priority FROM mcp_tool",
                (rs, rowNum) -> new GlobalPriority(rs.getObject("id", UUID.class), rs.getDouble("priority")));
        Map<UUID, Double> byTool = new HashMap<>();
        for (GlobalPriority row : rows) {
            byTool.put(row.toolId(), row.priority());
        }
        return byTool;
    }

    private record GlobalPriority(UUID toolId, double priority) {}

    @Override
    public void updateGlobalPriority(@NonNull UUID toolId, double priority, int avgLatencyMs) {
        jdbcTemplate.update(
                "UPDATE mcp_tool SET priority = ?, avg_latency_ms = ? WHERE id = ?", priority, avgLatencyMs, toolId);
    }

    private static ToolPriorityOverlay mapOverlay(ResultSet rs, int rowNum) throws SQLException {
        return new ToolPriorityOverlay(
                rs.getObject("tool_id", UUID.class), rs.getDouble("priority"), rs.getInt("avg_latency_ms"));
    }

    private static ToolInvocationStats mapStats(ResultSet rs, int rowNum) throws SQLException {
        return new ToolInvocationStats(
                rs.getObject("tool_id", UUID.class),
                rs.getLong("total_calls"),
                rs.getLong("success_count"),
                rs.getLong("latency_sum_ms"),
                rs.getLong("fallback_count"));
    }
}
