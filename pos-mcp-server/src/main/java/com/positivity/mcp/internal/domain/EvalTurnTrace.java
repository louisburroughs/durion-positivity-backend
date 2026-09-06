package com.positivity.mcp.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Immutable, replayable evidence captured for one alpha chat turn. */
public record EvalTurnTrace(
        @NonNull UUID turnId,
        @NonNull Instant startedAt,
        @NonNull Instant completedAt,
        @NonNull Instant expiresAt,
        @NonNull UUID userId,
        @NonNull String username,
        @NonNull String role,
        @NonNull String userMessage,
        @Nullable Boolean simpleChat,
        @Nullable String intent,
        @Nullable String modelTier,
        @Nullable String workflowState,
        @NonNull List<String> selectedTools,
        @Nullable String systemPrompt,
        @NonNull List<ToolDefinitionTrace> offeredTools,
        @NonNull List<ToolCallTrace> toolCalls,
        @Nullable String finalResponse,
        @Nullable String error,
        @Nullable String serverBuild) {

    public EvalTurnTrace {
        selectedTools = List.copyOf(selectedTools);
        offeredTools = List.copyOf(offeredTools);
        toolCalls = List.copyOf(toolCalls);
    }

    public record ToolDefinitionTrace(
            @NonNull String name,
            @NonNull String description,
            @NonNull String inputSchema) {}

    public record ToolCallTrace(
            int sequence,
            @NonNull String name,
            @NonNull String arguments,
            @Nullable String result,
            @Nullable String error,
            int elapsedMs) {}
}
