package com.positivity.mcp.internal.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
@ConditionalOnProperty(name = "mcp.tuning.enabled", havingValue = "true", matchIfMissing = true)
public class ToolPriorityTuningService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ToolPriorityTuningService.class);

  private final JdbcTemplate jdbcTemplate;

  public ToolPriorityTuningService(@NonNull JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Scheduled(cron = "${mcp.tuning.cron:0 0 2 * * ?}")
  public void tuneToolPriorities() {
    Timestamp cutoff = Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS));
    String statsQuery = """
        SELECT tool_id,
               COUNT(*) AS total_calls,
               AVG(CASE WHEN success THEN 1.0 ELSE 0.0 END) AS success_rate,
               AVG(execution_time_ms) AS avg_latency,
               AVG(CASE WHEN fallback_invoked THEN 1.0 ELSE 0.0 END) AS fallback_rate
        FROM mcp_tool_invocation_log
        WHERE created_at > ?
          AND tool_id IS NOT NULL
          AND execution_time_ms >= 0
        GROUP BY tool_id
        HAVING COUNT(*) >= 10
        """;

    List<ToolPerformanceStats> stats = jdbcTemplate.query(
        statsQuery,
        (resultSet, rowNum) -> new ToolPerformanceStats(
            resultSet.getObject("tool_id", UUID.class),
            resultSet.getDouble("success_rate"),
            resultSet.getDouble("avg_latency"),
            resultSet.getDouble("fallback_rate")),
        cutoff);

    int tuned = 0;
    for (ToolPerformanceStats stat : stats) {
      Double currentPriority = jdbcTemplate.queryForObject(
          "SELECT priority FROM mcp_tool WHERE id = ?",
          Double.class,
          stat.toolId());

      if (currentPriority == null) {
        continue;
      }

      double performanceScore = (stat.successRate() * 0.6)
          + ((1 - Math.min(stat.avgLatency() / 2000.0, 1.0)) * 0.3)
          - (stat.fallbackRate() * 0.2);
      double clampedScore = clamp(performanceScore, 0.1, 1.0);
      double newPriority = (currentPriority * 0.7) + (clampedScore * 0.3);

      jdbcTemplate.update(
          "UPDATE mcp_tool SET priority = ?, avg_latency_ms = CAST(? AS INT) WHERE id = ?",
          newPriority,
          stat.avgLatency(),
          stat.toolId());
      tuned++;
    }

    LOGGER.info("Tuned priorities for {} tools", tuned);
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private record ToolPerformanceStats(
      @Nullable UUID toolId,
      double successRate,
      double avgLatency,
      double fallbackRate) {
  }
}
