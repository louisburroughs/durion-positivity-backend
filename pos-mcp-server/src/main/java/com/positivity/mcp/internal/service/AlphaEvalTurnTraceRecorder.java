package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.ScopeTrace;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.domain.TagTrace;
import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import com.positivity.mcp.internal.scopegraph.ScopeMetrics;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
     * The turn the current thread is recording. Stays private: {@code EvalTurnTracePropagation}
     * carries it across Reactor threads through {@link #currentTurnHandle()}, {@link #bindTurn} and
     * {@link #unbindTurn()} rather than by reaching into the field (#1850).
     */
    private final ThreadLocal<TraceBuilder> activeTurn = new ThreadLocal<>();

    /** ADR-0069: the rollout mode stamped on a recorded scope; {@code off} when not wired. */
    private final ScopeGraphProperties scopeGraphProperties;

    /** ADR-0069: the in-scope share meters, fed when a turn completes; absent when not wired. */
    private final @Nullable ScopeMetrics scopeMetrics;

    /** ADR-0068: the enforced-tag list stamped on a recorded tag record; nothing enforced when not wired. */
    private final TaggingProperties taggingProperties;

    /** The pre-ADR-0069 construction: a recorded scope is traced, nothing is measured. */
    public AlphaEvalTurnTraceRecorder(
            @NonNull EvalTurnTraceRepository repository,
            @NonNull Clock clock,
            @NonNull Duration retention,
            @NonNull String buildId) {
        this(repository, clock, retention, buildId, null, null, null);
    }

    /** The pre-ADR-0068 construction: no tagging switches. */
    public AlphaEvalTurnTraceRecorder(
            @NonNull EvalTurnTraceRepository repository,
            @NonNull Clock clock,
            @NonNull Duration retention,
            @NonNull String buildId,
            @Nullable ScopeGraphProperties scopeGraphProperties,
            @Nullable ScopeMetrics scopeMetrics) {
        this(repository, clock, retention, buildId, scopeGraphProperties, scopeMetrics, null);
    }

    @Autowired
    public AlphaEvalTurnTraceRecorder(
            @NonNull EvalTurnTraceRepository repository,
            @NonNull Clock clock,
            @Value("${mcp.eval.turn-trace.retention:24h}") @NonNull Duration retention,
            @Value("${mcp.build.id:unknown}") @NonNull String buildId,
            @Nullable ScopeGraphProperties scopeGraphProperties,
            @Nullable ScopeMetrics scopeMetrics,
            @Nullable TaggingProperties taggingProperties) {
        this.repository = repository;
        this.clock = clock;
        this.retention = retention;
        this.buildId = buildId;
        this.scopeGraphProperties = scopeGraphProperties == null ? ScopeGraphProperties.off() : scopeGraphProperties;
        this.scopeMetrics = scopeMetrics;
        this.taggingProperties = taggingProperties == null ? TaggingProperties.off() : taggingProperties;
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
     * ADR-0069 §9: the {@code mcp_tool.name} a recorded tool call resolved to. A facade callback is
     * named after its {@code @Tool} method while the catalog, and with it the scope, knows the facade
     * by its class name, so the in-scope share is computed from these names and not from the names
     * in the trace's tool calls. A call to a tool that was never offered has no catalog name and is
     * in no scope.
     */
    public void recordCalledCatalogTool(@NonNull String catalogToolName) {
        current(builder -> builder.calledCatalogTools.add(catalogToolName));
    }

    /** ADR-0068 §6: the tag record of this turn (both taggers' answers); never called with {@code none()}. */
    public void recordTags(@NonNull QuestionTags tags) {
        current(builder -> builder.tags = tags);
    }

    /** ADR-0069 §9: the scope resolved for this turn. Not called in mode {@code off} or for simple chat. */
    public void recordScope(@NonNull ScopeSet scope) {
        current(builder -> builder.scope = scope);
    }

    /** ADR-0069 §6: the tools the scope added this turn, and whether the RAG hook narrowed retrieval. */
    public void recordScopeConsumers(@NonNull List<String> addedTools, boolean ragFilterApplied) {
        current(builder -> {
            builder.scopeAddedTools = List.copyOf(addedTools);
            builder.scopeRagFilterApplied = ragFilterApplied;
        });
    }

    /**
     * ADR-0069 §9: the {@code document_id}s of the final top-K a retrieval handed to the model.
     * A turn may retrieve more than once; the documents accumulate, each counted once.
     */
    public void recordRetrievedDocuments(@NonNull Collection<String> documentIds) {
        current(builder -> {
            if (builder.retrievedDocuments == null) {
                builder.retrievedDocuments = new LinkedHashSet<>();
            }
            builder.retrievedDocuments.addAll(documentIds);
        });
    }

    /**
     * How the reply was produced — direct model content, the #1708 re-render, the ladder, or a
     * pass-through of a non-content source — so grading can tell an answered question from a
     * deflected one without reading the server log (#1816).
     */
    public void recordAnswerSource(@NonNull String answerSource) {
        current(builder -> builder.answerSource = answerSource);
    }

    /** #2075: the conversation and the assistant message this turn produces (null when not persisted). */
    public void recordMessage(@Nullable UUID conversationId, @Nullable UUID messageId) {
        current(builder -> {
            builder.conversationId = conversationId;
            builder.messageId = messageId;
        });
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
        synchronized (builder) {
            finishLocked(builder, response, error);
        }
    }

    private void finishLocked(@NonNull TraceBuilder builder, @Nullable String response, @Nullable String error) {
        try {
            EvalTurnTrace trace =
                    builder.build(clock.instant(), retention, response, error, scopeGraphProperties, taggingProperties);
            repository.save(trace);
            measureScope(trace.scope());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to persist alpha evaluation turn trace", exception);
        } finally {
            activeTurn.remove();
        }
    }

    /** The in-scope shares are counted only for a turn whose trace was written, so the two agree. */
    private void measureScope(@Nullable ScopeTrace scope) {
        if (scope == null || scopeMetrics == null) {
            return;
        }
        if (scope.calledTools() != null && scope.calledToolsInScope() != null) {
            scopeMetrics.recordCalledTools(scope.calledToolsInScope(), scope.calledTools());
        }
        if (scope.retrievedDocs() != null && scope.retrievedDocsInScope() != null) {
            scopeMetrics.recordRetrievedDocuments(scope.retrievedDocsInScope(), scope.retrievedDocs());
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

    /**
     * Binds {@code handle} as this thread's active turn. For context propagation, which has to set
     * and clear the binding around a signal rather than around a call; ordinary callers want
     * {@link #runWithTurn}, which restores the previous binding for them.
     */
    public void bindTurn(@Nullable Object handle) {
        if (handle instanceof TraceBuilder builder) {
            activeTurn.set(builder);
        } else {
            activeTurn.remove();
        }
    }

    /** Clears this thread's binding, leaving the turn itself untouched. */
    public void unbindTurn() {
        activeTurn.remove();
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

    /**
     * Applies {@code operation} to this thread's turn, synchronized on the builder.
     *
     * <p>Before #1850 a turn was one thread's by construction and plain fields were safe. Now a
     * streamed turn's tool calls and completion run on different threads against the same builder,
     * so every mutation — and the read in {@link #finish} — takes the builder's monitor. Contention
     * is nil: these are short field writes on one turn.
     */
    private void current(java.util.function.Consumer<TraceBuilder> operation) {
        TraceBuilder builder = activeTurn.get();
        if (builder != null) {
            synchronized (builder) {
                operation.accept(builder);
            }
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
        private UUID conversationId;
        private UUID messageId;
        private List<String> selectedTools = List.of();
        private String systemPrompt;
        private List<EvalTurnTrace.ToolDefinitionTrace> offeredTools = List.of();
        private final List<EvalTurnTrace.ToolCallTrace> toolCalls = new ArrayList<>();
        /** The {@code mcp_tool.name} of each executed call; a call to an unknown tool adds nothing. */
        private final List<String> calledCatalogTools = new ArrayList<>();

        private ScopeSet scope;
        private QuestionTags tags;
        private List<String> scopeAddedTools = List.of();
        private boolean scopeRagFilterApplied;
        /** Null until a retrieval was observed for the turn. */
        private Set<String> retrievedDocuments;

        private final String buildId;

        private TraceBuilder(CurrentUserContext user, String userMessage, Instant startedAt, String buildId) {
            this.user = user;
            this.userMessage = userMessage;
            this.startedAt = startedAt;
            this.buildId = buildId;
        }

        private EvalTurnTrace build(
                Instant completedAt,
                Duration retention,
                @Nullable String response,
                @Nullable String error,
                ScopeGraphProperties scopeGraphProperties,
                TaggingProperties taggingProperties) {
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
                    answerSource,
                    conversationId,
                    messageId,
                    scopeTrace(scopeGraphProperties),
                    tagTrace(taggingProperties));
        }

        /**
         * ADR-0068 §6 / spec §2.8: one entry per tag either tagger answered, with the acting answer,
         * both taggers' values and their agreement. Values are booleans, enum names and option labels
         * only. Null when no record was stamped on the turn.
         */
        private @Nullable TagTrace tagTrace(TaggingProperties taggingProperties) {
            if (tags == null || tags.isNone()) {
                return null;
            }
            Set<String> names = new java.util.TreeSet<>(tags.heuristic().keySet());
            names.addAll(tags.model().keySet());
            List<TagTrace.TagEntry> entries = new ArrayList<>();
            for (String name : names) {
                TagAnswer acting = tags.acting().get(name);
                TagAnswer heuristic = tags.heuristic().get(name);
                TagAnswer model = tags.model().get(name);
                entries.add(new TagTrace.TagEntry(
                        name,
                        acting == null ? null : acting.value(),
                        acting == null ? null : acting.source().name(),
                        heuristic == null ? null : heuristic.value(),
                        model == null ? null : model.value(),
                        model == null ? null : model.confidence(),
                        heuristic == null || model == null ? null : QuestionTags.agrees(heuristic, model)));
            }
            return new TagTrace(
                    tags.mode().name(),
                    tags.mode() == com.positivity.mcp.internal.domain.TaggingMode.ENFORCE
                            ? taggingProperties.enforcedTags()
                            : List.of(),
                    tags.providerModel(),
                    tags.latencyMs(),
                    tags.fallbackReason() == null ? null : tags.fallbackReason().wireName(),
                    tags.stateTruncated(),
                    entries);
        }

        /** ADR-0069 §9 / spec §2.11: the recorded scope plus the in-scope shares, known only now. */
        private @Nullable ScopeTrace scopeTrace(ScopeGraphProperties scopeGraphProperties) {
            if (scope == null) {
                return null;
            }
            Set<String> scopeTools = scope.toolNames();
            int calledInScope = (int)
                    calledCatalogTools.stream().filter(scopeTools::contains).count();
            Integer retrieved = retrievedDocuments == null ? null : retrievedDocuments.size();
            Integer retrievedInScope = retrievedDocuments == null
                    ? null
                    : (int) retrievedDocuments.stream()
                            .filter(scope.documentIds()::contains)
                            .count();
            return new ScopeTrace(
                    scopeGraphProperties.mode().name(),
                    scopeGraphProperties.mode() == ScopeGraphProperties.Mode.ENFORCE
                            ? scopeGraphProperties.enforce().stream()
                                    .map(Enum::name)
                                    .toList()
                            : List.of(),
                    scope.graphHash(),
                    scope.graphBuiltAt(),
                    scope.confidence().name(),
                    scope.seeds().stream()
                            .map(seed -> new ScopeTrace.SeedTrace(
                                    seed.entity(), seed.kind().name()))
                            .toList(),
                    scope.seeds().size() + scope.reachedEntities().size(),
                    scope.tools().size(),
                    scope.documentIds().size(),
                    scope.screenKeys().size(),
                    scopeAddedTools.size(),
                    scopeRagFilterApplied,
                    calledInScope,
                    toolCalls.size(),
                    retrievedInScope,
                    retrieved);
        }
    }
}
