package com.positivity.mcp.internal.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class OllamaChatModelConfiguration {

    @Bean
    @Primary
    public @NonNull ChatModel chatModel(
            @Value("${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}") @NonNull String baseUrl,
            @Value("${OLLAMA_CHAT_MODEL:qwen3.5:cloud}") @NonNull String modelName,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey,
            @Value("${OLLAMA_CHAT_TIMEOUT:180s}") @NonNull Duration timeout) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .customHeaders(authHeaders(apiKey))
                .temperature(0.2)
                .timeout(timeout)
                .build();
    }

    @Bean
    @Primary
    public @NonNull StreamingChatModel streamingChatModel(
            @Value("${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}") @NonNull String baseUrl,
            @Value("${OLLAMA_CHAT_MODEL:qwen3.5:cloud}") @NonNull String modelName,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey,
            @Value("${OLLAMA_STREAMING_CHAT_TIMEOUT:${OLLAMA_CHAT_TIMEOUT:180s}}") @NonNull Duration timeout) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .customHeaders(authHeaders(apiKey))
                .temperature(0.2)
                .timeout(timeout)
                .build();
    }

    private static @NonNull Map<String, String> authHeaders(@NonNull String apiKey) {
        Map<String, String> headers = new HashMap<>();
        if (!apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }
}
