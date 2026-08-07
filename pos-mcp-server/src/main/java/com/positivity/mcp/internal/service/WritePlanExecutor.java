package com.positivity.mcp.internal.service;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Gate 6 (#1193): executes a confirmed write plan's tool call using the plan's exact persisted
 * arguments. Implementations must never derive arguments from anywhere but {@code argsJson} —
 * re-parsing user text at execution time is the documented gate-mismatch failure mode.
 */
public interface WritePlanExecutor {

    /**
     * Executes {@code targetTool} with the exact persisted {@code argsJson}, relaying the caller's
     * {@code Authorization} header downstream.
     *
     * @return the tool's rendered result
     * @throws com.positivity.mcp.internal.exception.WritePlanExecutionException when the tool is
     *     unknown, lacks execution coordinates, or the downstream call fails
     */
    @NonNull
    String execute(@NonNull String targetTool, @NonNull String argsJson, @Nullable String authHeader);
}
