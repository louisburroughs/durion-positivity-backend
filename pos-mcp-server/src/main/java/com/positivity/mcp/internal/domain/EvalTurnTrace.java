package com.positivity.mcp.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, replayable evidence captured for one alpha chat turn.
 *
 * <p>{@code conversationId} and {@code messageId} (#2075) name the persisted conversation and the
 * assistant message the turn produced, so a rating can be joined to its trace; both are null on the
 * ephemeral path, and a payload written before #2075 reads them as null.
 */
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
        @Nullable String serverBuild,
        @Nullable String answerSource,
        @Nullable UUID conversationId,
        @Nullable UUID messageId) {

    public EvalTurnTrace {
        selectedTools = List.copyOf(selectedTools);
        offeredTools = List.copyOf(offeredTools);
        toolCalls = List.copyOf(toolCalls);
    }

    /** The pre-#2075 shape: no conversation or message link. */
    public EvalTurnTrace(
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
            @Nullable String serverBuild,
            @Nullable String answerSource) {
        this(
                turnId,
                startedAt,
                completedAt,
                expiresAt,
                userId,
                username,
                role,
                userMessage,
                simpleChat,
                intent,
                modelTier,
                workflowState,
                selectedTools,
                systemPrompt,
                offeredTools,
                toolCalls,
                finalResponse,
                error,
                serverBuild,
                answerSource,
                null,
                null);
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
