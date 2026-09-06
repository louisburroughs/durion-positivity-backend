package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

final class SpringAiStreamingPosAssistant implements StreamingPosAssistant {

    // Shared with the non-streaming assistant so both paths ground identically
    // (previously this path
    // carried only the bare "Relevant retrieved context:" header with no grounding
    // instruction).
    private static final String RAG_CONTEXT_PREFIX = RagGroundingInstruction.CONTEXT_PREFIX;

    private final StreamingChatModel streamingChatModel;

    /**
     * Owns the tool-execution loop. Required, not optional: a streaming-only bean could not execute
     * tools at all, and would silently answer every question without them — the exact failure this
     * class was changed to fix. Both production beans qualify ({@code OllamaChatModel} and
     * {@code TierScopedChatModel} each implement {@link ChatModel} and {@link StreamingChatModel}),
     * so this rejects a misconfiguration rather than a supported setup.
     */
    private final ChatClient chatClient;

    private final Supplier<String> systemPromptSupplier;
    private final List<ToolCallback> staticToolCallbacks;
    private final QueryDocumentRetriever ragRetriever;
    private final Function<String, ChatMemory> chatMemoryProvider;
    private final @Nullable OpenApiToolProvider openApiToolProvider;
    private final @Nullable RequestScopedUserContext requestScopedUserContext;
    private final @Nullable ToolInvocationRecorder invocationRecorder;

    SpringAiStreamingPosAssistant(
            @NonNull StreamingChatModel streamingChatModel,
            @NonNull Supplier<String> systemPromptSupplier,
            @NonNull List<Object> staticTools,
            @NonNull QueryDocumentRetriever ragRetriever,
            @NonNull Function<String, ChatMemory> chatMemoryProvider,
            @Nullable OpenApiToolProvider openApiToolProvider,
            @Nullable ToolInvocationRecorder invocationRecorder,
            @Nullable RequestScopedUserContext requestScopedUserContext,
            @Nullable ObservationRegistry observationRegistry) {
        this.streamingChatModel = streamingChatModel;
        if (!(streamingChatModel instanceof ChatModel chatModel)) {
            // Bean-wiring invariant checked once at construction time from Spring configuration,
            // never from a controller thread or client input (#1694) -- left as a bare
            // IllegalArgumentException.
            throw new IllegalArgumentException(
                    "Streaming chat model %s must also implement ChatModel: tool execution runs through ChatClient and would otherwise be silently unavailable"
                            .formatted(streamingChatModel.getClass().getName()));
        }
        this.chatClient =
                SpringAiPosAssistant.buildToolCallingChatClient(chatModel, observationRegistry, invocationRecorder);
        this.systemPromptSupplier = systemPromptSupplier;
        this.staticToolCallbacks = SpringAiToolCallbackResolver.fromObjects(staticTools, invocationRecorder);
        this.ragRetriever = ragRetriever;
        this.chatMemoryProvider = chatMemoryProvider;
        this.openApiToolProvider = openApiToolProvider;
        this.requestScopedUserContext = requestScopedUserContext;
        this.invocationRecorder = invocationRecorder;
    }

    @Override
    public @NonNull Flux<String> chat(
            @NonNull String memoryId, @NonNull String userMessage, @NonNull String userContext) {
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
        // ToolCallingAdvisor executes streamed tool calls on Schedulers.boundedElastic(), after this
        // request's ThreadLocal caller has been cleared on the assembly thread. Bind it now or every
        // streamed invocation is audited as "unknown" and loses its correlation id.
        toolCallbacks = CallerBoundToolCallback.bindCurrentCaller(toolCallbacks, requestScopedUserContext);
        String systemPrompt = buildSystemPrompt(userMessage, userContext);
        List<Message> promptMessages = new ArrayList<>(chatMemory.get(memoryId));
        promptMessages.add(new SystemMessage(systemPrompt));
        promptMessages.add(new UserMessage(userMessage));
        chatMemory.add(memoryId, List.of(new UserMessage(userMessage)));
        AtomicReference<StringBuilder> responseText = new AtomicReference<>(new StringBuilder());
        Prompt prompt =
                new Prompt(promptMessages, SpringAiPosAssistant.toolCallingOptions(defaultOptions(), toolCallbacks));
        // As of Spring AI 2.0 the tool-execution loop lives in ChatClient's ToolCallingAdvisor;
        // StreamingChatModel.stream only advertises the tool definitions and streams the model's
        // tool-call turn back unexecuted.
        Flux<String> tokens = chatClient.prompt(prompt).stream()
                .chatResponse()
                .map(response -> {
                    if (response.getResult() == null || response.getResult().getOutput() == null) {
                        return "";
                    }
                    String text = response.getResult().getOutput().getText();
                    return text == null ? "" : text;
                })
                .filter(token -> !token.isEmpty());
        // #1838: the same classification the blocking path applies, so harmony markup, a bare
        // payload or a blank reply never reaches the client as the answer. Memory stores what the
        // client saw.
        return StreamingAnswerGuard.guard(tokens, this::recordAnswerSource)
                .doOnNext(token -> responseText.get().append(token))
                .doOnComplete(() -> {
                    String assistantResponse = responseText.get().toString();
                    if (!assistantResponse.isBlank()) {
                        chatMemory.add(memoryId, List.of(new AssistantMessage(assistantResponse)));
                    }
                });
    }

    private void recordAnswerSource(ChatResponseText.@NonNull Source source) {
        if (invocationRecorder != null) {
            invocationRecorder.recordAnswerSource(source.name());
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
     * Returns the streaming chat model's configured default options, used as the base for the
     * per-request tool-calling options. Only {@link ChatModel} exposes {@code getOptions()}; the
     * constructor guarantees the bean implements it.
     */
    private @Nullable ChatOptions defaultOptions() {
        return streamingChatModel instanceof ChatModel chatModel ? chatModel.getOptions() : null;
    }
}
