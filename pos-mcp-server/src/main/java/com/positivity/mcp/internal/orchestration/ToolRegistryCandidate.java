package com.positivity.mcp.internal.orchestration;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public record ToolRegistryCandidate(
        @NonNull UUID id,
        @NonNull String name,
        @NonNull String description,
        double score,
        @NonNull Map<String, Object> inputSchema
) {
}
