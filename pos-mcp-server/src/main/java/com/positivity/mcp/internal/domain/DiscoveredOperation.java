package com.positivity.mcp.internal.domain;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Execution metadata for an OpenAPI-discovered operation ({@code mcp_tool.source = 'openapi'}),
 * used to build an agent-callable tool (Gate 3). Kept separate from {@link ToolMetadata} so the
 * widely-used selection record is not disturbed.
 *
 * <p>{@code requiredPermissions} carries the operation's {@code x-required-permissions} extension at
 * discovery time (#781); it is granted to {@code mcp_tool_permission} during persistence and is
 * <strong>not</strong> stored on the {@code mcp_tool} row itself. It is empty when the extension is
 * absent — discovered ops are then fail-closed (never selected) until a permission is granted.
 */
public record DiscoveredOperation(
        @NonNull String name,
        @NonNull String description,
        @Nullable String httpMethod,
        @Nullable String httpPath,
        @Nullable String serviceId,
        @Nullable String inputSchema,
        @NonNull List<String> requiredPermissions) {

    /** True when the persisted execution coordinates are sufficient to build a proxy call. */
    public boolean isExecutable() {
        return httpMethod != null && httpPath != null && serviceId != null;
    }
}
