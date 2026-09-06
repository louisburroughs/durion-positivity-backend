package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Captures replay-relevant I/O for the currently active alpha evaluation turn.
 */
@Component
@Profile("alpha")
@ConditionalOnProperty(name = "mcp.eval.turn-trace.enabled", havingValue = "true")
public class AlphaEvalTurnTraceRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlphaEvalTurnTraceRecorder.class);

    private final EvalTurnTraceRepository repository;
    private final Clock clock;
    private final Duration retention;
    // The deployed build (image tag, MCP_BUILD_ID) stamped on every trace, so a gate run can say
    // which build answered each turn — including a deploy that lands mid-run (#1806).
    private final String buildId;
    /**
     * The turn the current thread is recording. Package-private so {@link EvalTurnTracePropagation}
     * can register it with Micrometer's {@code ContextRegistry}: a streamed turn runs its tool calls
     * and its completion on Reactor threads, and without propagation every record from those threads
     * lands on nothing (#1850).
     */
    final ThreadLocal<TraceBuilder> activeTurn = new ThreadLocal<>();

    public AlphaEvalTurnTraceRecorder(
            @NonNull EvalTurnTraceRepository repository,
            @NonNull Clock clock,
            @Value("${mcp.eval.turn-trace.retention:24h}") @NonNull Duration retention,
            @Value("${mcp.build.id:unknown}") @NonNull String buildId) {
        this.repository = repository;
        this.clock = clock;
        this.retention = retention;
        this.buildId = buildId;
    }

    public void begin(@NonNull CurrentUserContext user, @NonNull String userMessage) {
        activeTurn.set(new TraceBuilder(user, userMessage, clock.instant(), buildId));
    }

    public void recordSimpleChat(boolean simpleChat) {
        current(builder -> builder.simpleChat = simpleChat);
    }

    public void recordRouting(@NonNull String intent, @NonNull String modelTier) {
        current(builder -> {
            builder.intent = intent;
            builder.modelTier = modelTier;
        });
    }

    public void recordWorkflowState(@NonNull String workflowState) {
        current(builder -> builder.workflowState = workflowState);
    }

    public void recordSelectedTools(@NonNull List<String> selectedTools) {
        current(builder -> builder.selectedTools = List.copyOf(selectedTools));
    }

    public void recordPrompt(
            @NonNull String systemPrompt, @NonNull List<EvalTurnTrace.ToolDefinitionTrace> offeredTools) {
        current(builder -> {
            builder.systemPrompt = systemPrompt;
            builder.offeredTools = List.copyOf(offeredTools);
        });
    }

    public void recordToolCall(
            @NonNull String toolName,
            @NonNull String arguments,
            @Nullable String result,
            @Nullable String error,
            int elapsedMs) {
        current(builder -> builder.toolCalls.add(new EvalTurnTrace.ToolCallTrace(
                builder.toolCalls.size() + 1, toolName, arguments, result, error, elapsedMs)));
    }

    /**
     * How the reply was produced — direct model content, the #1708 re-render, the ladder, or a
     * pass-through of a non-content source — so grading can tell an answered question from a
     * deflected one without reading the server log (#1816).
     */
    public void recordAnswerSource(@NonNull String answerSource) {
        current(builder -> builder.answerSource = answerSource);
    }

    public void complete(@NonNull String response) {
        finish(response, null);
    }

    public void fail(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        String error = message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getClass().getSimpleName() + ": " + message;
        finish(null, error);
    }

    public void clear() {
        activeTurn.remove();
    }

    boolean hasActiveTurn() {
        return activeTurn.get() != null;
    }

    private void finish(@Nullable String response, @Nullable String error) {
        TraceBuilder builder = activeTurn.get();
        if (builder == null) {
            return;
        }
        try {
            repository.save(builder.build(clock.instant(), retention, response, error));
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to persist alpha evaluation turn trace", exception);
        } finally {
            activeTurn.remove();
        }
    }

    /**
     * The turn bound to this thread, as an opaque handle (#1850).
     *
     * <p>Captured on the request thread and handed back to {@link #runWithTurn} inside a Reactor
     * callback, this makes a streamed turn's completion deterministic rather than dependent on
     * context propagation having captured the right thread at subscribe time.
     */
    public @Nullable Object currentTurnHandle() {
        return activeTurn.get();
    }

    /** Runs {@code action} with {@code handle} bound as the active turn, restoring what was there. */
    public void runWithTurn(@Nullable Object handle, @NonNull Runnable action) {
        if (!(handle instanceof TraceBuilder builder)) {
            action.run();
            return;
        }
        TraceBuilder previous = activeTurn.get();
        activeTurn.set(builder);
        try {
            action.run();
        } finally {
            if (previous == null) {
                activeTurn.remove();
            } else {
                activeTurn.set(previous);
            }
        }
    }

    private void current(java.util.function.Consumer<TraceBuilder> operation) {
        TraceBuilder builder = activeTurn.get();
        if (builder != null) {
            operation.accept(builder);
        }
    }

    private static final class TraceBuilder {

        private final CurrentUserContext user;
        private final String userMessage;
        private final Instant startedAt;
        private Boolean simpleChat;
        private String intent;
        private String modelTier;
        private String workflowState;
        private String answerSource;
        private List<String> selectedTools = List.of();
        private String systemPrompt;
        private List<EvalTurnTrace.ToolDefinitionTrace> offeredTools = List.of();
        private final List<EvalTurnTrace.ToolCallTrace> toolCalls = new ArrayList<>();

        private final String buildId;

        private TraceBuilder(CurrentUserContext user, String userMessage, Instant startedAt, String buildId) {
            this.user = user;
            this.userMessage = userMessage;
            this.startedAt = startedAt;
            this.buildId = buildId;
        }

        private EvalTurnTrace build(
                Instant completedAt, Duration retention, @Nullable String response, @Nullable String error) {
            return new EvalTurnTrace(
                    UUIDv7Generator.generate(),
                    startedAt,
                    completedAt,
                    completedAt.plus(retention),
                    user.userId(),
                    user.username(),
                    user.primaryRole(),
                    userMessage,
                    simpleChat,
                    intent,
                    modelTier,
                    workflowState,
                    selectedTools,
                    systemPrompt,
                    offeredTools,
                    toolCalls,
                    response,
                    error,
                    buildId,
                    answerSource);
        }
    }
}
