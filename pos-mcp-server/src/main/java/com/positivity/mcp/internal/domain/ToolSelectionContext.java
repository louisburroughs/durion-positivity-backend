package com.positivity.mcp.internal.domain;

import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * @param role retained for system-prompt/RAG-scope selection, agent-cache namespacing, and
 *     admin fast-path keyword matching; it does <strong>not</strong> gate {@code mcp_tool}
 *     candidate selection
 * @param permissionCodes the {@code mcp_tool_permission} gating signal — see
 *     {@code com.positivity.mcp.service.CurrentUserContext#permissionCodes()}
 */
public record ToolSelectionContext(
        @NonNull String userInput,
        @NonNull String role,
        @NonNull String workflowState,
        @NonNull Set<String> permissionCodes) {}
