package com.positivity.mcp.internal.orchestration;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Test-scope replay harness for the offline replay eval (#1682). Package-placed
 * alongside
 * {@link SpringAiPosAssistant} so it can reuse its package-private bounded
 * {@code ToolCallingAdvisor} construction
 * ({@link SpringAiPosAssistant#buildToolCallingChatClient})
 * verbatim, rather than a second, drifting tool-execution loop.
 *
 * <p>
 * Never touches a backend service, the alpha database, or Eureka: tool
 * execution is replaced by
 * a fixture's canned {@link ToolResponseFixture} sequence, consumed in order
 * per tool name.
 */
final class OfflineReplayEvaluator {

    private OfflineReplayEvaluator() {}

    /**
     * One canned response a stub tool callback returns, in the order it is queued.
     */
    record ToolResponseFixture(
            @NonNull String toolName,
            @NonNull List<String> argumentsContains,
            @NonNull String response) {}

    /** One observed tool invocation during replay. */
    record ObservedToolCall(
            int sequence,
            @NonNull String name,
            @NonNull String arguments,
            boolean argumentsMatched,
            @Nullable String result,
            @Nullable String error) {}

    /** The full observed outcome of replaying one fixture against a real model. */
    record ReplayResult(
            @NonNull List<ObservedToolCall> toolCalls,
            @NonNull String finalResponse,
            @Nullable String error) {}

    /**
     * Builds a stub {@link ToolCallback} that never executes real tool logic: it
     * returns the next
     * queued canned response for {@code name}, recording each call (sequence,
     * arguments, whether the
     * declared required argument keys were present, and the returned response) into
     * the shared
     * {@code observedCalls} list so the caller can grade tool selection, argument
     * accuracy, and call
     * sequence afterward.
     */
    static @NonNull ToolCallback stubCallback(
            @NonNull String name,
            @NonNull String description,
            @NonNull String inputSchema,
            @NonNull Deque<ToolResponseFixture> responses,
            @NonNull List<ObservedToolCall> observedCalls) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                int sequence = observedCalls.size() + 1;
                ToolResponseFixture next = responses.poll();
                if (next == null) {
                    String error = "no canned response remaining for '" + name + "'";
                    observedCalls.add(new ObservedToolCall(sequence, name, toolInput, false, null, error));
                    return "{\"error\":\"" + error + "\"}";
                }
                boolean matched = argumentsContainAll(toolInput, next.argumentsContains());
                observedCalls.add(new ObservedToolCall(sequence, name, toolInput, matched, next.response(), null));
                return next.response();
            }
        };
    }

    /**
     * Builds an empty response queue as a mutable {@link Deque}, for
     * {@link #stubCallback}.
     */
    static @NonNull Deque<ToolResponseFixture> newResponseQueue() {
        return new ArrayDeque<>();
    }

    /**
     * Replays one turn through the same bounded {@code ToolCallingAdvisor} path
     * production uses,
     * recording every stub-tool call into {@code observedCalls} as a side effect
     * and returning the
     * model's final text (or the error a naming/model failure produced).
     */
    static @NonNull ReplayResult replay(
            @NonNull ChatModel chatModel,
            @NonNull String systemPrompt,
            @NonNull String userMessage,
            @NonNull List<ToolCallback> toolCallbacks,
            @NonNull List<ObservedToolCall> observedCalls) {
        ChatClient chatClient = SpringAiPosAssistant.buildToolCallingChatClient(chatModel, ObservationRegistry.NOOP);
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(systemPrompt));
        promptMessages.add(new UserMessage(userMessage));
        Prompt prompt = new Prompt(
                promptMessages, SpringAiPosAssistant.toolCallingOptions(chatModel.getOptions(), toolCallbacks));
        try {
            ChatResponse chatResponse = chatClient.prompt(prompt).call().chatResponse();
            AssistantMessage output = chatResponse != null && chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput()
                    : null;
            String text = ChatResponseText.extract(output);
            return new ReplayResult(List.copyOf(observedCalls), text, null);
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            String error = exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
            return new ReplayResult(List.copyOf(observedCalls), "", error);
        }
    }

    private static boolean argumentsContainAll(@NonNull String toolInputJson, @NonNull List<String> requiredKeys) {
        for (String key : requiredKeys) {
            if (!toolInputJson.contains("\"" + key + "\"")) {
                return false;
            }
        }
        return true;
    }
}
