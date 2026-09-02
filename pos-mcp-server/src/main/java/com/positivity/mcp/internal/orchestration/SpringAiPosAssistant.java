package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.AnswerResolutionLadder;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
import org.springframework.ai.tool.ToolCallback;

final class SpringAiPosAssistant implements PosAssistant {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiPosAssistant.class);

    /**
     * Shared grounding instruction ({@link RagGroundingInstruction}) prepended to
     * the RAG snippets.
     */
    private static final String RAG_CONTEXT_PREFIX = RagGroundingInstruction.CONTEXT_PREFIX;

    private final ChatModel chatModel;
    private final ChatClient chatClient;
    private final Supplier<String> systemPromptSupplier;
    private final List<ToolCallback> staticToolCallbacks;
    private final QueryDocumentRetriever ragRetriever;
    private final Function<String, ChatMemory> chatMemoryProvider;
    private final @Nullable OpenApiToolProvider openApiToolProvider;
    private final @Nullable AnswerResolutionLadder answerResolutionLadder;

    SpringAiPosAssistant(
            @NonNull ChatModel chatModel,
            @NonNull Supplier<String> systemPromptSupplier,
            @NonNull List<Object> staticTools,
            @NonNull QueryDocumentRetriever ragRetriever,
            @NonNull Function<String, ChatMemory> chatMemoryProvider,
            @Nullable OpenApiToolProvider openApiToolProvider,
            @Nullable AnswerResolutionLadder answerResolutionLadder,
            @Nullable ToolInvocationRecorder invocationRecorder) {
        this.chatModel = chatModel;
        // Tool execution lives in ChatClient's ToolCallingAdvisor, not in ChatModel — see chat().
        this.chatClient = ChatClient.builder(chatModel).build();
        this.systemPromptSupplier = systemPromptSupplier;
        this.staticToolCallbacks = SpringAiToolCallbackResolver.fromObjects(staticTools, invocationRecorder);
        this.ragRetriever = ragRetriever;
        this.chatMemoryProvider = chatMemoryProvider;
        this.openApiToolProvider = openApiToolProvider;
        this.answerResolutionLadder = answerResolutionLadder;
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
        String systemPrompt = buildSystemPrompt(userMessage, userContext);
        List<Message> promptMessages = new ArrayList<>(chatMemory.get(memoryId));
        promptMessages.add(new SystemMessage(systemPrompt));
        promptMessages.add(new UserMessage(userMessage));

        // Must go through ChatClient, not ChatModel.call: as of Spring AI 2.0 the tool-execution loop
        // lives in ChatClient's ToolCallingAdvisor. ChatModel.call only advertises the tool
        // definitions and returns the model's tool-call turn verbatim — nothing runs the tool, and
        // such a turn carries empty content, so the reply degraded to recovered reasoning or a
        // ladder hand-off and no tool was ever invoked.
        ChatResponse chatResponse = chatClient
                .prompt(new Prompt(promptMessages, toolCallingOptions(chatModel.getOptions(), toolCallbacks)))
                .call()
                .chatResponse();
        AssistantMessage output = chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput()
                : null;
        ChatResponseText.Extracted extracted = ChatResponseText.extractDetailed(output);
        logMissingDirectAnswer(chatResponse, output, extracted, toolCallbacks.size());
        String response = resolveResponse(userMessage, extracted);
        chatMemory.add(memoryId, List.of(new UserMessage(userMessage), new AssistantMessage(response)));
        return response;
    }

    /**
     * Returns the model's direct answer when it produced one. When it did not
     * (blank {@code content},
     * so the text would otherwise be recovered thinking or the blank fallback) and
     * a ladder is wired,
     * hand off to the ladder rather than surface the reasoning channel. With no
     * ladder, behaviour is
     * unchanged — the extracted text (including thinking recovery) is returned.
     */
    private @NonNull String resolveResponse(
            @NonNull String userMessage, ChatResponseText.@NonNull Extracted extracted) {
        if (answerResolutionLadder != null && extracted.source() != ChatResponseText.Source.CONTENT) {
            return answerResolutionLadder.resolveFallback(userMessage).text();
        }
        return extracted.text();
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
                toolCalls == null ? 0 : toolCalls.size(),
                offeredToolCount,
                metadata != null ? metadata.getFinishReason() : null);
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
