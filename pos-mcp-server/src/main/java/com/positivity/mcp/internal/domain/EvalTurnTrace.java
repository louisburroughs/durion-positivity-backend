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
 *
 * <p>{@code scope} (ADR-0069 §9) is the turn's scope resolution in {@code shadow} and {@code
 * enforce}; it is null in mode {@code off}, on the simple-chat path, and in every payload written
 * before it existed.
 *
 * <p>{@code tags} (ADR-0068 §6) is the turn's question tagging: both taggers' answers and their
 * agreement in {@code shadow} and {@code enforce}, the heuristic answers alone in {@code off}; null in
 * every payload written before it existed.
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
        @Nullable UUID messageId,
        @Nullable ScopeTrace scope,
        @Nullable TagTrace tags) {

    public EvalTurnTrace {
        selectedTools = List.copyOf(selectedTools);
        offeredTools = List.copyOf(offeredTools);
        toolCalls = List.copyOf(toolCalls);
    }

    /** The pre-ADR-0068 shape: a scope, no tags. */
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
            @Nullable String answerSource,
            @Nullable UUID conversationId,
            @Nullable UUID messageId,
            @Nullable ScopeTrace scope) {
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
                conversationId,
                messageId,
                scope,
                null);
    }

    /** The pre-ADR-0069 shape: no scope. */
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
            @Nullable String answerSource,
            @Nullable UUID conversationId,
            @Nullable UUID messageId) {
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
                conversationId,
                messageId,
                null,
                null);
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
                null,
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
