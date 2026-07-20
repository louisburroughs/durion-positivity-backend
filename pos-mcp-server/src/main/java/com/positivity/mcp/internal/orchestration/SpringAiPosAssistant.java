package com.positivity.mcp.internal.orchestration;

import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import java.util.ArrayList;
import java.util.List;

final class SpringAiPosAssistant implements PosAssistant {

    private final ChatModel chatModel;
    private final Supplier<String> systemPromptSupplier;
    private final List<ToolCallback> staticToolCallbacks;
    private final @Nullable OpenApiToolProvider openApiToolProvider;

    SpringAiPosAssistant(
            @NonNull ChatModel chatModel,
            @NonNull Supplier<String> systemPromptSupplier,
            @NonNull List<Object> staticTools,
            @Nullable OpenApiToolProvider openApiToolProvider) {
        this.chatModel = chatModel;
        this.systemPromptSupplier = systemPromptSupplier;
        this.staticToolCallbacks = SpringAiToolCallbackResolver.fromObjects(staticTools);
        this.openApiToolProvider = openApiToolProvider;
    }

    @Override
    public @NonNull String chat(@NonNull String memoryId, @NonNull String userMessage, @NonNull String userContext) {
        String systemPrompt = systemPromptSupplier.get() + System.lineSeparator() + userContext;
        List<ToolCallback> toolCallbacks = new ArrayList<>(staticToolCallbacks);
        if (openApiToolProvider != null) {
            toolCallbacks.addAll(openApiToolProvider.resolveToolCallbacks(userMessage));
        }
        return chatModel.call(new Prompt(
                        List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)),
                        DefaultToolCallingChatOptions.builder().toolCallbacks(toolCallbacks).build()))
                .getResult()
                .getOutput()
                .getText();
    }
}
