package com.positivity.mcp.internal.domain;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Execution metadata for an OpenAPI-discovered operation ({@code mcp_tool.source = 'openapi'}),
 * used to build an agent-callable tool (Gate 3). Kept separate from {@link ToolMetadata} so the
 * widely-used selection record is not disturbed.
 */
public record DiscoveredOperation(
        @NonNull String name,
        @NonNull String description,
        @Nullable String httpMethod,
        @Nullable String httpPath,
        @Nullable String serviceId,
        @Nullable String inputSchema) {

    /** True when the persisted execution coordinates are sufficient to build a proxy call. */
    public boolean isExecutable() {
        return httpMethod != null && httpPath != null && serviceId != null;
    }
}
