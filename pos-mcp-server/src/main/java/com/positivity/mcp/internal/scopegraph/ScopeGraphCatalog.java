package com.positivity.mcp.internal.scopegraph;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The whole tool and screen catalog as the scope-graph builder reads it (spec §2.4): every row, not
 * one caller's view. Caller filtering happens per turn, after expansion (ADR-0069 §5.3).
 */
public record ScopeGraphCatalog(
        @NonNull List<ToolRow> tools,
        @NonNull List<ToolPermissionRow> toolPermissions,
        @NonNull List<ToolWorkflowRow> toolWorkflowStates,
        @NonNull List<ToolPrerequisiteRow> toolPrerequisites,
        @NonNull List<ScreenRow> screens) {

    /** The {@code mcp_tool.source} value of an OpenAPI-discovered operation; anything else is a facade. */
    public static final String SOURCE_OPENAPI = "openapi";

    public ScopeGraphCatalog {
        tools = List.copyOf(tools);
        toolPermissions = List.copyOf(toolPermissions);
        toolWorkflowStates = List.copyOf(toolWorkflowStates);
        toolPrerequisites = List.copyOf(toolPrerequisites);
        screens = List.copyOf(screens);
    }

    public static @NonNull ScopeGraphCatalog empty() {
        return new ScopeGraphCatalog(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** One {@code mcp_tool} row. Only enabled tools become nodes. */
    public record ToolRow(
            @NonNull String name,
            @NonNull String domain,
            @NonNull String source,
            @Nullable String httpMethod,
            boolean enabled) {

        public boolean discovered() {
            return SOURCE_OPENAPI.equals(source);
        }
    }

    /** One {@code mcp_tool_permission} row, by tool name. */
    public record ToolPermissionRow(
            @NonNull String toolName,
            @NonNull String permissionCode,
            @NonNull String permissionGroup) {}

    /** One {@code mcp_tool_workflow} row, by tool and state name. */
    public record ToolWorkflowRow(
            @NonNull String toolName, @NonNull String workflowState) {}

    /** One {@code mcp_tool_prerequisite} row: {@code producingTool} supplies {@code requiredParam} of {@code toolName}. */
    public record ToolPrerequisiteRow(
            @NonNull String toolName,
            @NonNull String requiredParam,
            @NonNull String producingTool) {}

    /** One {@code mcp_screen_registry} row. */
    public record ScreenRow(
            @NonNull String screenKey,
            @NonNull String domain,
            @Nullable String requiredPerm,
            @NonNull String urlTemplate,
            @NonNull String title) {}
}
