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
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

final class SpringAiStreamingPosAssistant implements StreamingPosAssistant {

    private static final String RAG_CONTEXT_PREFIX = "Relevant retrieved context:";
    private static final int MAX_CONTEXT_DOCS = 5;
    private static final int MAX_CONTEXT_CHARS = 4_000;

    private final StreamingChatModel streamingChatModel;
    private final @Nullable String modelName;
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
        this.modelName = resolveModelName(streamingChatModel);
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
        String systemPrompt = buildSystemPrompt(userMessage, userContext);
        List<Message> promptMessages = new ArrayList<>(chatMemory.get(memoryId));
        promptMessages.add(new SystemMessage(systemPrompt));
        promptMessages.add(new UserMessage(userMessage));
        chatMemory.add(memoryId, List.of(new UserMessage(userMessage)));

        List<ToolCallback> toolCallbacks = new ArrayList<>(staticToolCallbacks);
        if (openApiToolProvider != null) {
            toolCallbacks.addAll(openApiToolProvider.resolveToolCallbacks(userMessage));
        }
        AtomicReference<StringBuilder> responseText = new AtomicReference<>(new StringBuilder());
        DefaultToolCallingChatOptions.Builder optionsBuilder =
                DefaultToolCallingChatOptions.builder().toolCallbacks(toolCallbacks);
        // Only override the model when we resolved one; a null/blank override would clobber the
        // chat model's configured default and fail with "model cannot be null or empty".
        if (modelName != null && !modelName.isBlank()) {
            optionsBuilder.model(modelName);
        }
        return streamingChatModel.stream(new Prompt(promptMessages, optionsBuilder.build()))
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
        StringBuilder builder = new StringBuilder();
        List<org.springframework.ai.document.Document> documents = ragRetriever.retrieve(userMessage);
        int maxDocs = Math.min(MAX_CONTEXT_DOCS, documents.size());
        for (int index = 0; index < maxDocs; index++) {
            String text = documents.get(index).getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }
            builder.append("[").append(index + 1).append("] ").append(text.trim());
            if (builder.length() >= MAX_CONTEXT_CHARS) {
                builder.setLength(MAX_CONTEXT_CHARS);
                break;
            }
        }
        return builder.toString();
    }

    /**
     * Resolves the model configured on the streaming chat model's default options so that the
     * per-request tool-calling options carry it explicitly. Ollama's option merge treats the runtime
     * options' null {@code model} as an override and clobbers the configured default, which would fail
     * with "model cannot be null or empty"; passing the model through avoids that.
     */
    private static @Nullable String resolveModelName(@NonNull StreamingChatModel streamingChatModel) {
        // Only ChatModel exposes getDefaultOptions(); the concrete Ollama bean implements both.
        if (streamingChatModel instanceof ChatModel chatModel) {
            ChatOptions defaultOptions = chatModel.getDefaultOptions();
            return defaultOptions != null ? defaultOptions.getModel() : null;
        }
        return null;
    }
}
