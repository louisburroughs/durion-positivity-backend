package com.positivity.mcp.internal.domain;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

public record ToolMetadata(
        @NonNull UUID id,
        @NonNull String name,
        @NonNull String displayName,
        @NonNull String description,
        @NonNull String domain,
        double priority,
        @NonNull String costLevel,
        int avgLatencyMs,
        boolean enabled,
        @NonNull String handlerBean) {}
