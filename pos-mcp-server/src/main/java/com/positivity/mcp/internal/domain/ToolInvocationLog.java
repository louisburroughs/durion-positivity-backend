package com.positivity.mcp.internal.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ToolInvocationLog(
        @Nullable UUID toolId,
        @Nullable String userId,
        @Nullable String sessionId,
        @Nullable String intent,
        @Nullable String workflowState,
        int semanticRank,
        double finalScore,
        boolean selected,
        boolean success,
        boolean fallbackInvoked,
        int executionTimeMs,
        @Nullable String errorType) {}
