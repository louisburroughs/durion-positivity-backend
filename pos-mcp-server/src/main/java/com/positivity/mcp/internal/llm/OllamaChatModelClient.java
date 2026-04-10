package com.positivity.mcp.internal.llm;

import com.positivity.mcp.internal.config.LlmApiProperties;
import com.positivity.mcp.internal.config.LlmRuntimeProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(prefix = "mcp.llm.runtime", name = "provider", havingValue = "ollama")
public class OllamaChatModelClient implements ChatModelClient {

    private final WebClient.Builder webClientBuilder;
    private final LlmRuntimeProperties runtimeProperties;
    private final LlmApiProperties apiProperties;

    public OllamaChatModelClient(
            WebClient.Builder webClientBuilder,
            LlmRuntimeProperties runtimeProperties,
            LlmApiProperties apiProperties) {
        this.webClientBuilder = webClientBuilder;
        this.runtimeProperties = runtimeProperties;
        this.apiProperties = apiProperties;
    }

    @Override
    public @NonNull Mono<ChatCompletion> complete(@NonNull ChatRequest request) {
        LlmApiProperties.LlmApi api = resolveApi(runtimeProperties.defaultApiId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", api.model());
        payload.put("stream", false);
        payload.put("messages", request.messages().stream()
                .map(message -> Map.of(
                        "role", message.role(),
                        "content", message.content()))
                .toList());

        if (!request.tools().isEmpty()) {
            payload.put("tools", request.tools().stream()
                    .map(tool -> Map.of(
                            "type", "function",
                            "function", Map.of(
                                    "name", tool.name(),
                                    "description", tool.description(),
                                    "parameters", tool.inputSchema())))
                    .toList());
        }

        WebClient client = webClientBuilder.build();
        return client.post()
                .uri(api.baseUrl())
                .headers(headers -> api.headers().forEach(headers::add))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(runtimeProperties.readTimeout())
                .map(this::toCompletion);
    }

    private LlmApiProperties.LlmApi resolveApi(String apiId) {
        return apiProperties.apis().stream()
                .filter(api -> api.id().equals(apiId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unknown LLM API id: " + apiId));
    }

    @SuppressWarnings("unchecked")
    private ChatCompletion toCompletion(Map<String, Object> response) {
        Object messageObj = response.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) {
            return new ChatCompletion("", List.of(), true);
        }

        Object contentValue = message.containsKey("content") ? message.get("content") : "";
        String content = String.valueOf(contentValue);
        Object toolCallsObj = message.get("tool_calls");
        if (!(toolCallsObj instanceof List<?> toolCalls)) {
            return new ChatCompletion(content, List.of(), true);
        }

        List<ToolCall> mappedCalls = toolCalls.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::toToolCall)
                .toList();
        return new ChatCompletion(content, mappedCalls, mappedCalls.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private ToolCall toToolCall(Map<?, ?> toolCall) {
        Object functionObj = toolCall.get("function");
        if (!(functionObj instanceof Map<?, ?> functionMap)) {
            return new ToolCall("unknown", Map.of());
        }

        Object nameValue = functionMap.containsKey("name") ? functionMap.get("name") : "unknown";
        String name = String.valueOf(nameValue);
        Object arguments = functionMap.get("arguments");
        if (arguments instanceof Map<?, ?> mapArguments) {
            return new ToolCall(name, (Map<String, Object>) mapArguments);
        }
        return new ToolCall(name, Map.of());
    }
}
