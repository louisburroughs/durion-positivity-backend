package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.ToolInvocationStats;
import com.positivity.mcp.internal.domain.ToolPriorityOverlay;
import com.positivity.mcp.internal.repository.ToolPriorityRepository;
import com.positivity.tenancy.PlatformScoped;
import com.positivity.tenancy.TenantAudited;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nightly adaptive tool-priority tuning (Gate 7, #1195), per tenant with a global rollup (ADR-0062
 * plan WS6, decided 2026-09-10).
 *
 * <p>The run sweeps the active tenants through {@link TenantIterator}. Bound to each tenant in
 * turn it reads that tenant's own invocation log (row-level security shows it nothing else) and
 * tunes that tenant's overlay row per tool with enough recent calls, starting a new overlay from
 * the global priority. The per-tenant aggregates are summed in memory and, once the sweep is over,
 * the global row on {@code mcp_tool} is recomputed from all tenants' logs in the platform-scoped
 * {@link #recomputeGlobalPriorities} step: the only way to see every tenant's history from a
 * non-owner connection is to add up what each tenant's binding showed, never a cross-tenant query.
 * A tenant with no history contributes nothing and keeps no overlay, so its requests fall back to
 * the global set.
 *
 * <p>Behavior is governed by {@code mcp.tuning.mode} ({@link TuningMode}): {@code off} skips the
 * run, {@code shadow} computes proposals and emits them to the structured logger
 * {@code mcp.tuning.shadow} plus the {@code mcp.tuning.proposals} counter without persisting
 * anything, and {@code live} writes proposals — but only when the most recent eval baseline
 * ({@code mcp.tuning.eval-result-path}) reports {@code thresholds.passed=true} and is younger than
 * {@code mcp.tuning.eval-freshness-hours}. A missing, stale, unreadable, or failed baseline
 * downgrades a live run to shadow behavior with a WARN, so live tuning can never promote past a
 * failing eval gate. The gate is evaluated once per run and applies to every tenant and the global
 * row alike.
 */
@Service
@Profile("!test")
public class ToolPriorityTuningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolPriorityTuningService.class);

    /** Dedicated structured logger for shadow-mode proposals (one JSON line per proposal). */
    private static final Logger SHADOW_LOGGER = LoggerFactory.getLogger("mcp.tuning.shadow");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PROPOSALS_COUNTER = "mcp.tuning.proposals";
    private static final String SCOPE_TENANT = "tenant";
    private static final String SCOPE_GLOBAL = "global";

    /** Tools with fewer executed calls than this in the window are not tuned in that scope. */
    static final int MIN_CALLS = 10;

    static final int WINDOW_DAYS = 7;

    private final ToolPriorityRepository repository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final TuningMode mode;
    private final Path evalResultPath;
    private final long evalFreshnessHours;
    private final TenantIterator tenantIterator;

    public ToolPriorityTuningService(
            @NonNull ToolPriorityRepository repository,
            @NonNull Clock clock,
            @NonNull MeterRegistry meterRegistry,
            @Value("${mcp.tuning.mode:}") @Nullable String configuredMode,
            @Value("${mcp.tuning.enabled:}") @Nullable String legacyEnabled,
            @Value("${mcp.tuning.eval-result-path:target/eval/baseline-live-python.json}") @NonNull
                    String evalResultPath,
            @Value("${mcp.tuning.eval-freshness-hours:48}") long evalFreshnessHours,
            @NonNull TenantIterator tenantIterator) {
        this.repository = repository;
        this.tenantIterator = tenantIterator;
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

    /**
     * Per tenant (ADR-0062 §3), then the platform-scoped global rollup: each active tenant's log tunes
     * that tenant's overlay with the tenant bound; the global row is recomputed from the sum of the
     * per-tenant aggregates once the sweep is over.
     */
    @Scheduled(cron = "${mcp.tuning.cron:0 0 2 * * ?}")
    public void tuneToolPriorities() {
        if (mode == TuningMode.OFF) {
            LOGGER.debug("Tool priority tuning skipped: mcp.tuning.mode=off");
            return;
        }
        boolean applyLive = mode == TuningMode.LIVE && evalGateAllowsLive();
        Instant cutoff = Instant.now(clock).minus(WINDOW_DAYS, ChronoUnit.DAYS);

        Map<UUID, ToolInvocationStats> global = new LinkedHashMap<>();
        int tenants = tenantIterator.forEachActiveTenant(tenantId -> {
            List<ToolInvocationStats> stats = tenantInvocationStats(cutoff);
            for (ToolInvocationStats stat : stats) {
                global.merge(stat.toolId(), stat, ToolInvocationStats::plus);
            }
            tuneTenantOverlay(tenantId, stats, applyLive);
        });
        int globalProposals = recomputeGlobalPriorities(global, applyLive);
        LOGGER.info(
                "Tool priority tuning finished: tenants={} globalToolsWithHistory={} globalProposals={} mode={}",
                tenants,
                global.size(),
                globalProposals,
                applyLive ? "live" : "shadow");
    }

    /** The bound tenant's aggregates over the window; the repository statement is tenant-blind, RLS scopes it. */
    @TenantAudited(
            reason = "reads mcp_tool_invocation_log through the connection TenantIterator bound; the statement names"
                    + " no tenant, so it aggregates the bound tenant's rows alone")
    @NonNull
    List<ToolInvocationStats> tenantInvocationStats(@NonNull Instant cutoff) {
        return repository.invocationStatsSince(cutoff);
    }

    /**
     * Tunes the bound tenant's overlay from that tenant's own aggregates. A new overlay row starts
     * from the global priority; an existing one drifts from its own previous value.
     */
    private void tuneTenantOverlay(
            @NonNull UUID tenantId, @NonNull List<ToolInvocationStats> stats, boolean applyLive) {
        Map<UUID, ToolPriorityOverlay> overlay = repository.findOverlayForCurrentTenant();
        List<PriorityProposal> proposals = new ArrayList<>();
        for (ToolInvocationStats stat : stats) {
            if (stat.totalCalls() < MIN_CALLS) {
                continue;
            }
            ToolPriorityOverlay existing = overlay.get(stat.toolId());
            Optional<Double> current =
                    existing == null ? repository.findGlobalPriority(stat.toolId()) : Optional.of(existing.priority());
            current.ifPresent(priority -> proposals.add(propose(stat, priority)));
        }
        for (PriorityProposal proposal : proposals) {
            if (applyLive) {
                repository.upsertOverlay(proposal.stats().toolId(), proposal.newPriority(), (int)
                        Math.round(proposal.stats().avgLatencyMs()));
            } else {
                logShadowProposal(SCOPE_TENANT, tenantId, proposal);
            }
            meterRegistry
                    .counter(PROPOSALS_COUNTER, "mode", applyLive ? "live" : "shadow", "scope", SCOPE_TENANT)
                    .increment();
        }
        long invocations =
                stats.stream().mapToLong(ToolInvocationStats::totalCalls).sum();
        LOGGER.info(
                "Tool priority tuning tenant={} invocations={} toolsWithHistory={} proposals={} overlayRowsBefore={} mode={}",
                tenantId,
                invocations,
                stats.size(),
                proposals.size(),
                overlay.size(),
                applyLive ? "live" : "shadow");
    }

    /**
     * The global rollup: recomputes {@code mcp_tool.priority} from the sum of every tenant's
     * aggregates. Runs after the tenant sweep with nothing bound and touches {@code mcp_tool} alone,
     * a global table; the scoped log was already read tenant by tenant.
     *
     * @return the number of global proposals (written in live mode, logged in shadow mode)
     */
    @PlatformScoped(
            reason = "writes mcp_tool.priority, the global catalog row, from the per-tenant aggregates summed in"
                    + " memory during the TenantIterator sweep; reads no tenant-scoped table")
    int recomputeGlobalPriorities(@NonNull Map<UUID, ToolInvocationStats> global, boolean applyLive) {
        List<PriorityProposal> proposals = new ArrayList<>();
        for (ToolInvocationStats stat : global.values()) {
            if (stat.totalCalls() < MIN_CALLS) {
                continue;
            }
            repository.findGlobalPriority(stat.toolId()).ifPresent(priority -> proposals.add(propose(stat, priority)));
        }
        for (PriorityProposal proposal : proposals) {
            if (applyLive) {
                repository.updateGlobalPriority(proposal.stats().toolId(), proposal.newPriority(), (int)
                        Math.round(proposal.stats().avgLatencyMs()));
            } else {
                logShadowProposal(SCOPE_GLOBAL, null, proposal);
            }
            meterRegistry
                    .counter(PROPOSALS_COUNTER, "mode", applyLive ? "live" : "shadow", "scope", SCOPE_GLOBAL)
                    .increment();
        }
        if (applyLive) {
            LOGGER.info("Tuned global priorities for {} tools (mode=live, eval gate passed)", proposals.size());
        } else {
            LOGGER.info(
                    "Computed {} global priority proposals without writing (effective mode=shadow)", proposals.size());
        }
        return proposals.size();
    }

    /** The Gate 7 formula: a clamped performance score blended 30/70 into the current priority. */
    static @NonNull PriorityProposal propose(@NonNull ToolInvocationStats stat, double currentPriority) {
        double performanceScore = (stat.successRate() * 0.6)
                + ((1 - Math.min(stat.avgLatencyMs() / 2000.0, 1.0)) * 0.3)
                - (stat.fallbackRate() * 0.2);
        double clampedScore = clamp(performanceScore, 0.1, 1.0);
        double newPriority = (currentPriority * 0.7) + (clampedScore * 0.3);
        return new PriorityProposal(stat, currentPriority, newPriority);
    }

    private static void logShadowProposal(
            @NonNull String scope, @Nullable UUID tenantId, @NonNull PriorityProposal proposal) {
        SHADOW_LOGGER.info(
                "{\"scope\":\"{}\",\"tenant_id\":{},\"tool_id\":\"{}\",\"current_priority\":{},"
                        + "\"proposed_priority\":{},\"delta\":{},\"total_calls\":{},\"success_rate\":{},"
                        + "\"avg_latency_ms\":{},\"fallback_rate\":{}}",
                scope,
                tenantId == null ? "null" : "\"" + tenantId + "\"",
                proposal.stats().toolId(),
                format(proposal.currentPriority()),
                format(proposal.newPriority()),
                format(proposal.newPriority() - proposal.currentPriority()),
                proposal.stats().totalCalls(),
                format(proposal.stats().successRate()),
                format(proposal.stats().avgLatencyMs()),
                format(proposal.stats().fallbackRate()));
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

    record PriorityProposal(@NonNull ToolInvocationStats stats, double currentPriority, double newPriority) {}
}
