package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.LlmRuntimeProperties;
import com.positivity.mcp.internal.llm.ChatModelClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LlmToolOrchestrator {

    private final ChatModelClient chatModelClient;
    private final ToolRegistryResolver toolRegistryResolver;
    private final LlmRuntimeProperties runtimeProperties;

    public LlmToolOrchestrator(
            @NonNull ChatModelClient chatModelClient,
            @NonNull ToolRegistryResolver toolRegistryResolver,
            @NonNull LlmRuntimeProperties runtimeProperties) {
        this.chatModelClient = chatModelClient;
        this.toolRegistryResolver = toolRegistryResolver;
        this.runtimeProperties = runtimeProperties;
    }

    public @NonNull Mono<OrchestrationResult> orchestrate(@NonNull OrchestrationRequest request) {
        ToolSelectionContext selectionContext = new ToolSelectionContext(
                request.prompt(),
                request.role(),
                request.workflowState(),
                request.intent(),
                request.sessionId(),
                request.correlationId());

        return toolRegistryResolver.resolveCandidates(selectionContext)
                .flatMap(candidates -> chatModelClient.complete(new ChatModelClient.ChatRequest(
                        List.of(
                                new ChatModelClient.ChatMessage("system", runtimeProperties.systemPrompt()),
                                new ChatModelClient.ChatMessage("user", request.prompt())),
                        candidates.stream()
                                .map(candidate -> new ChatModelClient.ToolDefinition(
                                        candidate.name(),
                                        candidate.description(),
                                        candidate.inputSchema()))
                                .toList(),
                        false))
                        .map(completion -> new OrchestrationResult(
                                completion.content(),
                                completion.toolCalls(),
                                candidates,
                                completion.finalResponse())));
    }

    public record OrchestrationRequest(
            @NonNull String prompt,
            @NonNull String role,
            @NonNull String workflowState,
            String intent,
            UUID sessionId,
            UUID correlationId
    ) {
    }

    public record OrchestrationResult(
            String content,
            @NonNull List<ChatModelClient.ToolCall> toolCalls,
            @NonNull List<ToolRegistryCandidate> candidates,
            boolean finalResponse
    ) {
    }
}
