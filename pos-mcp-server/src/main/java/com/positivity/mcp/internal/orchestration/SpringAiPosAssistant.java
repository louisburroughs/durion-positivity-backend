package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.domain.EvalTurnTrace.ToolDefinitionTrace;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.AnswerResolutionLadder;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

final class SpringAiPosAssistant implements PosAssistant {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiPosAssistant.class);

    /**
     * Shared grounding instruction ({@link RagGroundingInstruction}) prepended to
     * the RAG snippets.
     */
    private static final String RAG_CONTEXT_PREFIX = RagGroundingInstruction.CONTEXT_PREFIX;

    /**
     * System instruction for the one tool-less re-render turn (#1708). The payload may be a tool
     * result, a partial result (one month of six), or even the argument object of a call the model
     * never made — so the instruction must not assert it answers the question, or the model will
     * invent figures from it.
     */
    private static final String RENDER_INSTRUCTION =
            "The previous assistant turn is a raw JSON object or array the assistant emitted instead of an "
                    + "answer. It may be a tool result, a partial result, or the arguments of a call that was "
                    + "never made. If it contains the data needed to answer the user's question, write the "
                    + "answer from it as a short, direct reply: state the figures with their units and the "
                    + "period or as-of date they cover, use a table when there are several rows, and do not "
                    + "mention JSON, tools or fields. If it does not answer the question, or covers only part "
                    + "of it, say so plainly and state only what the data shows — invent nothing. Do not call "
                    + "any tool. Reply with prose only, never with a JSON object or array.";

    private final ChatModel chatModel;
    private final ChatClient chatClient;
    private final Supplier<String> systemPromptSupplier;
    private final List<ToolCallback> staticToolCallbacks;
    private final QueryDocumentRetriever ragRetriever;
    private final Function<String, ChatMemory> chatMemoryProvider;
    private final @Nullable OpenApiToolProvider openApiToolProvider;
    private final @Nullable AnswerResolutionLadder answerResolutionLadder;
    private final @Nullable ToolInvocationRecorder invocationRecorder;
    private final @Nullable RequestScopedUserContext requestScopedUserContext;

    SpringAiPosAssistant(
            @NonNull ChatModel chatModel,
            @NonNull Supplier<String> systemPromptSupplier,
            @NonNull List<Object> staticTools,
            @NonNull QueryDocumentRetriever ragRetriever,
            @NonNull Function<String, ChatMemory> chatMemoryProvider,
            @Nullable OpenApiToolProvider openApiToolProvider,
            @Nullable AnswerResolutionLadder answerResolutionLadder,
            @Nullable ToolInvocationRecorder invocationRecorder,
            @Nullable RequestScopedUserContext requestScopedUserContext,
            @Nullable ObservationRegistry observationRegistry) {
        this.chatModel = chatModel;
        this.chatClient = buildToolCallingChatClient(chatModel, observationRegistry);
        this.systemPromptSupplier = systemPromptSupplier;
        this.staticToolCallbacks = SpringAiToolCallbackResolver.fromObjects(staticTools, invocationRecorder);
        this.ragRetriever = ragRetriever;
        this.chatMemoryProvider = chatMemoryProvider;
        this.openApiToolProvider = openApiToolProvider;
        this.answerResolutionLadder = answerResolutionLadder;
        this.invocationRecorder = invocationRecorder;
        this.requestScopedUserContext = requestScopedUserContext;
    }

    @Override
    public @NonNull String chat(@NonNull String memoryId, @NonNull String userMessage, @NonNull String userContext) {
        ChatMemory chatMemory = chatMemoryProvider.apply(memoryId);
        // Tools are resolved BEFORE the system prompt so the per-request WRITE-GATE
        // signal
        // (recorded by OpenApiToolProvider in the request-scoped holder, #1193) is
        // visible to the
        // prompt supplier when it assembles the layered prompt.
        List<ToolCallback> toolCallbacks = new ArrayList<>(staticToolCallbacks);
        if (openApiToolProvider != null) {
            toolCallbacks.addAll(openApiToolProvider.resolveToolCallbacks(userMessage));
        }
        // Bind the caller per request: facade callbacks were wrapped once in the constructor and this
        // agent is cached per role, so the recorder must not read the caller at execution time.
        toolCallbacks = CallerBoundToolCallback.bindCurrentCaller(toolCallbacks, requestScopedUserContext);
        String systemPrompt = buildSystemPrompt(userMessage, userContext);
        if (invocationRecorder != null) {
            List<ToolDefinitionTrace> toolDefinitions = toolCallbacks.stream()
                    .map(ToolCallback::getToolDefinition)
                    .map(definition -> new ToolDefinitionTrace(
                            definition.name(), definition.description(), definition.inputSchema()))
                    .toList();
            invocationRecorder.recordPrompt(systemPrompt, toolDefinitions);
        }
        List<Message> history = chatMemory.get(memoryId);
        List<Message> promptMessages = new ArrayList<>(history);
        promptMessages.add(new SystemMessage(systemPrompt));
        promptMessages.add(new UserMessage(userMessage));

        // Must go through ChatClient, not ChatModel.call: as of Spring AI 2.0 the tool-execution loop
        // lives in ChatClient's ToolCallingAdvisor. ChatModel.call only advertises the tool
        // definitions and returns the model's tool-call turn verbatim — nothing runs the tool, and
        // such a turn carries empty content, so the reply degraded to recovered reasoning or a
        // ladder hand-off and no tool was ever invoked.
        ChatResponse chatResponse =
                callModel(new Prompt(promptMessages, toolCallingOptions(chatModel.getOptions(), toolCallbacks)));
        AssistantMessage output = chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput()
                : null;
        ChatResponseText.Extracted extracted = ChatResponseText.extractDetailed(output);
        Resolution resolution = resolveResponse(history, userMessage, extracted);
        if (!resolution.reRendered()) {
            logMissingDirectAnswer(chatResponse, output, extracted, toolCallbacks.size());
        }
        if (invocationRecorder != null) {
            invocationRecorder.recordAnswerSource(resolution.answerSource());
        }
        String response = resolution.text();
        chatMemory.add(memoryId, List.of(new UserMessage(userMessage), new AssistantMessage(response)));
        return response;
    }

    /**
     * The reply text and how it was produced. {@code answerSource} is recorded on the turn trace
     * (#1816): {@code CONTENT} (direct), {@code RE_RENDERED} (#1708 second turn), {@code LADDER}
     * (deflection), or the raw non-content source when no ladder is wired.
     */
    record Resolution(@NonNull String text, @NonNull String answerSource) {
        static final String CONTENT = "CONTENT";
        static final String RE_RENDERED = "RE_RENDERED";
        static final String LADDER = "LADDER";

        boolean reRendered() {
            return RE_RENDERED.equals(answerSource);
        }
    }

    /**
     * Returns the model's direct answer when it produced one. A bare tool payload ({@code
     * TOOL_PAYLOAD}) first gets one tool-less re-render turn; its prose is the reply. When the
     * model did not answer (blank {@code content}, so the text would otherwise be recovered
     * thinking or the blank fallback; or a payload the re-render could not turn into prose) and a
     * ladder is wired, hand off to the ladder rather than surface the reasoning channel. With no
     * ladder, behaviour is unchanged — the extracted text (including thinking recovery) is returned.
     */
    private @NonNull Resolution resolveResponse(
            @NonNull List<Message> history,
            @NonNull String userMessage,
            ChatResponseText.@NonNull Extracted extracted) {
        if (extracted.source() == ChatResponseText.Source.TOOL_PAYLOAD) {
            // The tools ran and the data is in hand; only the composition stage was skipped. One
            // re-render turn recovers the answer where the ladder would deflect (#1708).
            String rendered = renderToolPayload(history, userMessage, extracted.text());
            if (rendered != null) {
                return new Resolution(rendered, Resolution.RE_RENDERED);
            }
        }
        if (answerResolutionLadder != null && extracted.source() != ChatResponseText.Source.CONTENT) {
            return new Resolution(
                    answerResolutionLadder.resolveFallback(userMessage).text(), Resolution.LADDER);
        }
        return new Resolution(extracted.text(), extracted.source().name());
    }

    /**
     * Asks the model, once and without tools, to compose the answer from a bare tool payload it
     * emitted as its reply. Returns the prose, or {@code null} when the second turn produced no
     * direct answer either — the caller then falls back to the ladder as before.
     *
     * <p>Seen live on 2026-09-05 (q05): the guard from PR #1726 classified the payload correctly and
     * the ladder answered "I can't compute that directly, but you can view it here" — a deflection
     * for a turn whose tools had all run. The data was already in hand; only the last stage was
     * missing.
     *
     * <p>The render prompt carries the conversation history (so a follow-up such as "and what open
     * work orders do they have?" keeps its referent) but not the layered system prompt or the RAG
     * block: those exist to drive tool selection and grounding, and this turn offers no tools. The
     * as-of / period conventions come from the payload itself, which the instruction asks for.
     * Latency: this is a second full model call on a path that only runs after the guard fired.
     */
    private @Nullable String renderToolPayload(
            @NonNull List<Message> history, @NonNull String userMessage, @NonNull String payload) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(RENDER_INSTRUCTION));
        messages.addAll(history);
        messages.add(new UserMessage(userMessage));
        messages.add(new AssistantMessage(payload));
        messages.add(new UserMessage("Answer my question from the result above, as prose."));
        ChatResponse rendered = callModel(new Prompt(messages, chatModel.getOptions()));
        AssistantMessage output = rendered != null && rendered.getResult() != null
                ? rendered.getResult().getOutput()
                : null;
        ChatResponseText.Extracted extracted = ChatResponseText.extractDetailed(output);
        if (extracted.source() == ChatResponseText.Source.CONTENT) {
            LOGGER.info("Bare tool payload re-rendered as prose (#1708)");
            return extracted.text();
        }
        LOGGER.warn(
                "Bare tool payload re-render produced no direct answer either: source={} (#1708)", extracted.source());
        return null;
    }

    /**
     * Records why a turn produced no direct answer.
     *
     * <p>A blank {@code content} is otherwise indistinguishable from a turn in which the model
     * requested a tool that was never executed — both surface only as recovered thinking or a ladder
     * hand-off. Logging the unexecuted tool-call count and finish reason alongside the extraction
     * source separates "the model never asked for a tool" from "it asked and the call was dropped",
     * which cannot be determined from the outside today.
     */
    private void logMissingDirectAnswer(
            @Nullable ChatResponse chatResponse,
            @Nullable AssistantMessage output,
            ChatResponseText.@NonNull Extracted extracted,
            int offeredToolCount) {
        if (extracted.source() == ChatResponseText.Source.CONTENT) {
            return;
        }
        var result = chatResponse != null ? chatResponse.getResult() : null;
        var metadata = result != null ? result.getMetadata() : null;
        List<AssistantMessage.ToolCall> toolCalls = output != null ? output.getToolCalls() : List.of();
        LOGGER.warn(
                "Chat turn produced no direct answer: source={}, unexecutedToolCalls={}, offeredTools={}, finishReason={}",
                extracted.source(),
                toolCalls.size(),
                offeredToolCount,
                metadata != null ? metadata.getFinishReason() : null);
    }

    /**
     * Builds the client that owns the tool-execution loop.
     *
     * <p>The {@link ToolCallingAdvisor} is supplied explicitly rather than left to the client's
     * auto-registration so the loop is bounded (see {@link BoundedToolCallingManager}).
     *
     * <p>Deliberately NOT the Spring Boot autoconfigured {@code ChatClient.Builder} bean: a
     * hand-built manager has no bean-name {@code ToolCallbackResolver}, so only the callbacks carried
     * in the per-request prompt options are executable. That is what makes the permission gate
     * airtight — with bean resolution, a model naming a tool the caller is not entitled to could
     * execute it regardless of the gated callback list.
     */
    static @NonNull ChatClient buildToolCallingChatClient(
            @NonNull ChatModel chatModel, @Nullable ObservationRegistry observationRegistry) {
        // #1655: the single-argument ChatClient.builder(chatModel) hardcodes ObservationRegistry.NOOP,
        // which silently drops the spring.ai.chat.client observation and every per-advisor one. The
        // model-level gen_ai.client.operation metrics survive it (the Ollama bean carries its own
        // registry), so the gap is invisible from the metrics that do appear. Now that tools actually
        // execute (#1653), the advisor observations are the per-tool-call trace #1601 criterion 2 asks
        // for.
        return ChatClient.builder(
                        chatModel,
                        observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry,
                        null,
                        null)
                .defaultAdvisors(ToolCallingAdvisor.builder()
                        .toolCallingManager(new BoundedToolCallingManager(
                                ToolCallingManager.builder().build()))
                        .build())
                .build();
    }

    /**
     * Runs the turn, degrading to the blank-response path rather than propagating.
     *
     * <p>A model that names a tool which is not in the callback list makes
     * {@code DefaultToolCallingManager} throw a raw {@code IllegalStateException} ("No ToolCallback
     * found for tool name"), which the session manager would turn into a 500. With sixteen facades in
     * context that is a realistic model slip, and the whole point of the answer-resolution ladder is
     * that this path degrades instead of erroring.
     */
    private @Nullable ChatResponse callModel(@NonNull Prompt prompt) {
        try {
            return chatClient.prompt(prompt).call().chatResponse();
        } catch (ToolExecutionException exception) {
            // Distinguished from a model failure (#1711). Reaching here means the tool-calling loop
            // did NOT convert the failure into a result the model could correct — either the
            // configured processor rethrows, or the failure escaped a path that never had one. That
            // is a wiring fault worth its own signal, not the same event as the model timing out.
            LOGGER.error(
                    "Tool execution failed and was not converted into a model-readable result; "
                            + "the model cannot retry this turn",
                    exception);
            return null;
        } catch (RuntimeException exception) {
            LOGGER.warn("Chat turn failed during the model call; falling back", exception);
            return null;
        }
    }

    private @NonNull String buildSystemPrompt(@NonNull String userMessage, @NonNull String userContext) {
        StringBuilder promptBuilder = new StringBuilder(systemPromptSupplier.get())
                .append(System.lineSeparator())
                .append(userContext);
        String ragContext = ragContext(userMessage);
        if (!ragContext.isBlank()) {
            promptBuilder
                    .append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(RAG_CONTEXT_PREFIX)
                    .append(System.lineSeparator())
                    .append(ragContext);
        }
        return promptBuilder.toString();
    }

    private @NonNull String ragContext(@NonNull String userMessage) {
        return RagContextBuilder.build(ragRetriever.retrieve(userMessage));
    }

    /**
     * Builds the per-request tool-calling options by copying the chat model's
     * configured default
     * options and attaching the resolved tool callbacks.
     *
     * <p>
     * The copy must retain the provider-specific options type:
     * {@code OllamaChatModel} casts the
     * prompt's runtime options directly to {@code OllamaChatOptions}, so a generic
     * {@link DefaultToolCallingChatOptions} would fail with
     * {@code ClassCastException}. Copying the
     * default options via {@code mutate()} also preserves the configured model,
     * avoiding the
     * "model cannot be null or empty" failure that a fresh options object (with a
     * null model) would
     * trigger through Ollama's option merge.
     */
    static @NonNull ChatOptions toolCallingOptions(
            @Nullable ChatOptions defaultOptions, @NonNull List<ToolCallback> toolCallbacks) {
        if (defaultOptions instanceof ToolCallingChatOptions toolCallingDefaults) {
            return toolCallingDefaults.mutate().toolCallbacks(toolCallbacks).build();
        }
        DefaultToolCallingChatOptions.Builder builder =
                DefaultToolCallingChatOptions.builder().toolCallbacks(toolCallbacks);
        String model = defaultOptions != null ? defaultOptions.getModel() : null;
        if (model != null && !model.isBlank()) {
            builder.model(model);
        }
        return builder.build();
    }
}
