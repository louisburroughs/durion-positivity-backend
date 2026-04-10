package com.positivity.mcp.internal.llm;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Mono;

public interface ChatModelClient {

    @NonNull
    Mono<ChatCompletion> complete(@NonNull ChatRequest request);

    record ChatRequest(
            @NonNull List<ChatMessage> messages,
            @NonNull List<ToolDefinition> tools,
            boolean toolChoiceRequired
    ) {
    }

    record ChatMessage(
            @NonNull String role,
            @NonNull String content
    ) {
    }

    record ToolDefinition(
            @NonNull String name,
            @NonNull String description,
            @NonNull Map<String, Object> inputSchema
    ) {
    }

    record ToolCall(
            @NonNull String name,
            @NonNull Map<String, Object> arguments
    ) {
    }

    record ChatCompletion(
            String content,
            @NonNull List<ToolCall> toolCalls,
            boolean finalResponse
    ) {
    }
}
