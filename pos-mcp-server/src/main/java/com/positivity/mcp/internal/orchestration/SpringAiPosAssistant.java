package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

final class SpringAiPosAssistant implements PosAssistant {

    private static final String RAG_CONTEXT_PREFIX = "Relevant retrieved context:";
    private static final int MAX_CONTEXT_DOCS = 5;
    private static final int MAX_CONTEXT_CHARS = 4_000;

    private final ChatModel chatModel;
    private final Supplier<String> systemPromptSupplier;
    private final List<ToolCallback> staticToolCallbacks;
    private final QueryDocumentRetriever ragRetriever;
    private final Function<String, ChatMemory> chatMemoryProvider;
    private final @Nullable OpenApiToolProvider openApiToolProvider;

    SpringAiPosAssistant(
            @NonNull ChatModel chatModel,
            @NonNull Supplier<String> systemPromptSupplier,
            @NonNull List<Object> staticTools,
            @NonNull QueryDocumentRetriever ragRetriever,
            @NonNull Function<String, ChatMemory> chatMemoryProvider,
            @Nullable OpenApiToolProvider openApiToolProvider) {
        this.chatModel = chatModel;
        this.systemPromptSupplier = systemPromptSupplier;
        this.staticToolCallbacks = SpringAiToolCallbackResolver.fromObjects(staticTools);
        this.ragRetriever = ragRetriever;
        this.chatMemoryProvider = chatMemoryProvider;
        this.openApiToolProvider = openApiToolProvider;
    }

    @Override
    public @NonNull String chat(@NonNull String memoryId, @NonNull String userMessage, @NonNull String userContext) {
        ChatMemory chatMemory = chatMemoryProvider.apply(memoryId);
        String systemPrompt = buildSystemPrompt(userMessage, userContext);
        List<Message> promptMessages = new ArrayList<>(chatMemory.get(memoryId));
        promptMessages.add(new SystemMessage(systemPrompt));
        promptMessages.add(new UserMessage(userMessage));

        List<ToolCallback> toolCallbacks = new ArrayList<>(staticToolCallbacks);
        if (openApiToolProvider != null) {
            toolCallbacks.addAll(openApiToolProvider.resolveToolCallbacks(userMessage));
        }

        AssistantMessage output = chatModel
                .call(new Prompt(promptMessages, toolCallingOptions(chatModel.getDefaultOptions(), toolCallbacks)))
                .getResult()
                .getOutput();
        String response = ChatResponseText.extract(output);
        chatMemory.add(memoryId, List.of(new UserMessage(userMessage), new AssistantMessage(response)));
        return response;
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
     * Builds the per-request tool-calling options by copying the chat model's configured default
     * options and attaching the resolved tool callbacks.
     *
     * <p>The copy must retain the provider-specific options type: {@code OllamaChatModel} casts the
     * prompt's runtime options directly to {@code OllamaChatOptions}, so a generic
     * {@link DefaultToolCallingChatOptions} would fail with {@code ClassCastException}. Copying the
     * default options via {@code mutate()} also preserves the configured model, avoiding the
     * "model cannot be null or empty" failure that a fresh options object (with a null model) would
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
