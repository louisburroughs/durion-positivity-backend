package com.positivity.mcp.internal.domain;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Per-tool aggregate of {@code mcp_tool_invocation_log} over the tuning window, as sums rather than
 * rates so one tenant's aggregate can be {@linkplain #plus merged} into the global one without
 * re-reading the log (row-level security shows a connection one tenant's rows at a time, so the
 * global figure is the sum of the per-tenant sweeps, never a cross-tenant query).
 */
public record ToolInvocationStats(
        @NonNull UUID toolId, long totalCalls, long successCount, long latencySumMs, long fallbackCount) {

    public double successRate() {
        return totalCalls == 0 ? 0.0 : (double) successCount / totalCalls;
    }

    public double avgLatencyMs() {
        return totalCalls == 0 ? 0.0 : (double) latencySumMs / totalCalls;
    }

    public double fallbackRate() {
        return totalCalls == 0 ? 0.0 : (double) fallbackCount / totalCalls;
    }

    /** The sum of two aggregates of the same tool. */
    public @NonNull ToolInvocationStats plus(@NonNull ToolInvocationStats other) {
        if (!toolId.equals(other.toolId())) {
            throw new IllegalArgumentException("Cannot merge stats of tool " + toolId + " with " + other.toolId());
        }
        return new ToolInvocationStats(
                toolId,
                totalCalls + other.totalCalls(),
                successCount + other.successCount(),
                latencySumMs + other.latencySumMs(),
                fallbackCount + other.fallbackCount());
    }
}
