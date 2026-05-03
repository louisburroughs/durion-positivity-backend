package com.positivity.mcp.internal.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "mcp.model.fallback.enabled", havingValue = "true")
public class ModelFallbackConfiguration {

    @Bean("fallbackChatModel")
    public @NonNull ChatModel fallbackChatModel(
            @Value("${OLLAMA_FALLBACK_BASE_URL:${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}}") @NonNull String baseUrl,
            @Value("${mcp.model.fallback.secondary-model-name:qwen3-next:80b}") @NonNull String modelName,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey,
            @Value("${mcp.model.fallback.timeout:180s}") @NonNull Duration timeout) {
        OllamaChatModel.OllamaChatModelBuilder builder = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.2)
                .timeout(timeout);
        if (!apiKey.isBlank()) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + apiKey);
            builder.customHeaders(headers);
        }
        return builder.build();
    }
}
