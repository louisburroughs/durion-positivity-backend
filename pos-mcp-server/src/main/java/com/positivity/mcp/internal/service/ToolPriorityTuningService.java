package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nightly adaptive tool-priority tuning (Gate 7, #1195).
 *
 * <p>Behavior is governed by {@code mcp.tuning.mode} ({@link TuningMode}): {@code off} skips the
 * run, {@code shadow} computes proposals and emits them to the structured logger
 * {@code mcp.tuning.shadow} plus the {@code mcp.tuning.proposals} counter without persisting
 * anything, and {@code live} writes proposals — but only when the most recent eval baseline
 * ({@code mcp.tuning.eval-result-path}) reports {@code thresholds.passed=true} and is younger than
 * {@code mcp.tuning.eval-freshness-hours}. A missing, stale, unreadable, or failed baseline
 * downgrades a live run to shadow behavior with a WARN, so live tuning can never promote past a
 * failing eval gate.
 */
@Service
@Profile("!test")
public class ToolPriorityTuningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolPriorityTuningService.class);

    /** Dedicated structured logger for shadow-mode proposals (one JSON line per proposal). */
    private static final Logger SHADOW_LOGGER = LoggerFactory.getLogger("mcp.tuning.shadow");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PROPOSALS_COUNTER = "mcp.tuning.proposals";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final TuningMode mode;
    private final Path evalResultPath;
    private final long evalFreshnessHours;

    public ToolPriorityTuningService(
            @NonNull JdbcTemplate jdbcTemplate,
            @NonNull Clock clock,
            @NonNull MeterRegistry meterRegistry,
            @Value("${mcp.tuning.mode:}") @Nullable String configuredMode,
            @Value("${mcp.tuning.enabled:}") @Nullable String legacyEnabled,
            @Value("${mcp.tuning.eval-result-path:target/eval/baseline-live-python.json}") @NonNull
                    String evalResultPath,
            @Value("${mcp.tuning.eval-freshness-hours:48}") long evalFreshnessHours) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.mode = TuningMode.resolve(configuredMode, legacyEnabled);
        this.evalResultPath = Path.of(evalResultPath);
        this.evalFreshnessHours = evalFreshnessHours;
        if (TuningMode.isLegacyActivation(configuredMode, legacyEnabled)) {
            LOGGER.warn("mcp.tuning.enabled is deprecated; treating it as mcp.tuning.mode=live. "
                    + "Set mcp.tuning.mode=off|shadow|live (MCP_TUNING_MODE) instead.");
        }
    }

    /** Effective mode after {@code mcp.tuning.mode} / legacy {@code mcp.tuning.enabled} resolution. */
    public @NonNull TuningMode effectiveMode() {
        return mode;
    }

    @Scheduled(cron = "${mcp.tuning.cron:0 0 2 * * ?}")
    public void tuneToolPriorities() {
        if (mode == TuningMode.OFF) {
            LOGGER.debug("Tool priority tuning skipped: mcp.tuning.mode=off");
            return;
        }

        Timestamp cutoff = Timestamp.from(Instant.now(clock).minus(7, ChronoUnit.DAYS));
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

        List<PriorityProposal> proposals = new ArrayList<>();
        for (ToolPerformanceStats stat : stats) {
            Double currentPriority = jdbcTemplate.queryForObject(
                    "SELECT priority FROM mcp_tool WHERE id = ?", Double.class, stat.toolId());

            if (currentPriority == null) {
                continue;
            }

            double performanceScore = (stat.successRate() * 0.6)
                    + ((1 - Math.min(stat.avgLatency() / 2000.0, 1.0)) * 0.3)
                    - (stat.fallbackRate() * 0.2);
            double clampedScore = clamp(performanceScore, 0.1, 1.0);
            double newPriority = (currentPriority * 0.7) + (clampedScore * 0.3);
            proposals.add(new PriorityProposal(stat, currentPriority, newPriority));
        }

        boolean applyLive = mode == TuningMode.LIVE && evalGateAllowsLive();
        String effectiveModeTag = applyLive ? "live" : "shadow";

        for (PriorityProposal proposal : proposals) {
            if (applyLive) {
                jdbcTemplate.update(
                        "UPDATE mcp_tool SET priority = ?, avg_latency_ms = CAST(? AS INT) WHERE id = ?",
                        proposal.newPriority(),
                        proposal.stats().avgLatency(),
                        proposal.stats().toolId());
            } else {
                SHADOW_LOGGER.info(
                        "{\"tool_id\":\"{}\",\"current_priority\":{},\"proposed_priority\":{},\"delta\":{},"
                                + "\"success_rate\":{},\"avg_latency_ms\":{},\"fallback_rate\":{}}",
                        proposal.stats().toolId(),
                        format(proposal.currentPriority()),
                        format(proposal.newPriority()),
                        format(proposal.newPriority() - proposal.currentPriority()),
                        format(proposal.stats().successRate()),
                        format(proposal.stats().avgLatency()),
                        format(proposal.stats().fallbackRate()));
            }
            meterRegistry.counter(PROPOSALS_COUNTER, "mode", effectiveModeTag).increment();
        }

        if (applyLive) {
            LOGGER.info("Tuned priorities for {} tools (mode=live, eval gate passed)", proposals.size());
        } else {
            LOGGER.info("Computed {} priority proposals without writing (effective mode=shadow)", proposals.size());
        }
    }

    /**
     * Promotion gate for live tuning: the latest eval baseline file must exist, be younger than
     * {@code mcp.tuning.eval-freshness-hours}, and report {@code thresholds.passed=true}.
     */
    private boolean evalGateAllowsLive() {
        if (!Files.isRegularFile(evalResultPath)) {
            LOGGER.warn(
                    "Live tuning downgraded to shadow: eval baseline {} not found. Run scripts/eval_live.py first.",
                    evalResultPath);
            return false;
        }
        try {
            Instant modified = Files.getLastModifiedTime(evalResultPath).toInstant();
            Instant freshnessCutoff = Instant.now(clock).minus(evalFreshnessHours, ChronoUnit.HOURS);
            if (modified.isBefore(freshnessCutoff)) {
                LOGGER.warn(
                        "Live tuning downgraded to shadow: eval baseline {} is stale (mtime {}, freshness window {}h).",
                        evalResultPath,
                        modified,
                        evalFreshnessHours);
                return false;
            }
            JsonNode root = OBJECT_MAPPER.readTree(evalResultPath.toFile());
            boolean passed = root.path("thresholds").path("passed").asBoolean(false);
            if (!passed) {
                LOGGER.warn(
                        "Live tuning downgraded to shadow: eval baseline {} reports thresholds.passed=false ({}).",
                        evalResultPath,
                        root.path("thresholds").path("failures"));
            }
            return passed;
        } catch (IOException exception) {
            LOGGER.warn("Live tuning downgraded to shadow: failed to read eval baseline {}", evalResultPath, exception);
            return false;
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ToolPerformanceStats(
            @Nullable UUID toolId, double successRate, double avgLatency, double fallbackRate) {}

    private record PriorityProposal(@NonNull ToolPerformanceStats stats, double currentPriority, double newPriority) {}
}
