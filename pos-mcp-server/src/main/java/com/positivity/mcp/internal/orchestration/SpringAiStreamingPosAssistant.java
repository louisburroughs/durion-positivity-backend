package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
    private final Supplier<String> systemPromptSupplier;
    private final List<ToolCallback> staticToolCallbacks;
    private final QueryDocumentRetriever ragRetriever;
    private final Function<String, ChatMemory> chatMemoryProvider;
    private final @Nullable OpenApiToolProvider openApiToolProvider;

    SpringAiStreamingPosAssistant(
            @NonNull StreamingChatModel streamingChatModel,
            @NonNull Supplier<String> systemPromptSupplier,
            @NonNull List<Object> staticTools,
            @NonNull QueryDocumentRetriever ragRetriever,
            @NonNull Function<String, ChatMemory> chatMemoryProvider,
            @Nullable OpenApiToolProvider openApiToolProvider) {
        this.streamingChatModel = streamingChatModel;
        this.systemPromptSupplier = systemPromptSupplier;
        this.staticToolCallbacks = SpringAiToolCallbackResolver.fromObjects(staticTools);
        this.ragRetriever = ragRetriever;
        this.chatMemoryProvider = chatMemoryProvider;
        this.openApiToolProvider = openApiToolProvider;
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
        String systemPrompt = buildSystemPrompt(userMessage, userContext);
        List<Message> promptMessages = new ArrayList<>(chatMemory.get(memoryId));
        promptMessages.add(new SystemMessage(systemPrompt));
        promptMessages.add(new UserMessage(userMessage));
        chatMemory.add(memoryId, List.of(new UserMessage(userMessage)));
        AtomicReference<StringBuilder> responseText = new AtomicReference<>(new StringBuilder());
        return streamingChatModel.stream(new Prompt(
                        promptMessages, SpringAiPosAssistant.toolCallingOptions(defaultOptions(), toolCallbacks)))
                .map(response -> {
                    if (response.getResult() == null || response.getResult().getOutput() == null) {
                        return "";
                    }
                    return response.getResult().getOutput().getText();
                })
                .doOnNext(token -> responseText.get().append(token))
                .doOnComplete(() -> {
                    String assistantResponse = responseText.get().toString();
                    if (!assistantResponse.isBlank()) {
                        chatMemory.add(memoryId, List.of(new AssistantMessage(assistantResponse)));
                    }
                })
                .filter(token -> !token.isEmpty());
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
     * Returns the streaming chat model's configured default options, used as the
     * base for the
     * per-request tool-calling options. Only {@link ChatModel} exposes
     * {@code getOptions()};
     * the concrete Ollama bean implements both {@link StreamingChatModel} and
     * {@link ChatModel}.
     */
    private @Nullable ChatOptions defaultOptions() {
        return streamingChatModel instanceof ChatModel chatModel ? chatModel.getOptions() : null;
    }
}
