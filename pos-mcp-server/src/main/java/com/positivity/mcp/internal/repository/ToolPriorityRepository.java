package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolInvocationStats;
import com.positivity.mcp.internal.domain.ToolPriorityOverlay;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Tool-priority store with its tenant dimension (ADR-0062 plan WS6).
 *
 * <p>Two rows sets: the global priority on {@code mcp_tool} (a global, code-first catalog table that
 * every tenant shares) and the per-tenant overlay {@code mcp_tool_priority} (tenant-scoped under
 * row-level security). Every overlay read and write here is implicitly for the bound tenant: the
 * connection carries {@code app.current_tenant}, the policy shows only that tenant's rows, and the
 * column default stamps it on insert. Unbound, the overlay reads as empty and refuses writes.
 */
public interface ToolPriorityRepository {

    /** The bound tenant's overlay rows, keyed by tool id; empty for a tenant with no tuned tool. */
    @NonNull
    Map<UUID, ToolPriorityOverlay> findOverlayForCurrentTenant();

    /** Writes (or replaces) the bound tenant's overlay row for {@code toolId}. */
    void upsertOverlay(@NonNull UUID toolId, double priority, int avgLatencyMs);

    /**
     * The bound tenant's per-tool invocation aggregates since {@code cutoff}: executed invocations
     * only ({@code execution_time_ms >= 0}) that could be attributed to a tool ({@code tool_id IS NOT
     * NULL}). No minimum-call threshold: the caller applies it per scope.
     */
    @NonNull
    List<ToolInvocationStats> invocationStatsSince(@NonNull Instant cutoff);

    /**
     * Every tool's global priority from {@code mcp_tool}, keyed by tool id; a tool missing from the
     * map no longer exists.
     *
     * <p>The whole catalog in one query rather than a lookup per tool: {@code mcp_tool} is global and
     * small, while the caller needs a priority per qualifying tool <em>per tenant</em>, so a
     * per-lookup API costs O(tenants x tools) serial round trips for a table that answers in one.
     */
    @NonNull
    Map<UUID, Double> findGlobalPriorities();

    /** Writes the global priority and latency of a tool on {@code mcp_tool}. */
    void updateGlobalPriority(@NonNull UUID toolId, double priority, int avgLatencyMs);
}
