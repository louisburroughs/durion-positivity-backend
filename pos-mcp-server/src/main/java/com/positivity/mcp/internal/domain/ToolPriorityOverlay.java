package com.positivity.mcp.internal.domain;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * One tenant's tuned priority for one tool (ADR-0062 plan WS6): a row of {@code mcp_tool_priority},
 * which the bound tenant's connection alone can see. The global row stays on {@code mcp_tool}.
 */
public record ToolPriorityOverlay(@NonNull UUID toolId, double priority, int avgLatencyMs) {}
