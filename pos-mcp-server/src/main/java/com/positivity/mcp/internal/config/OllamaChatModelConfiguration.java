package com.positivity.mcp.internal.config;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@Profile("!test")
public class OllamaChatModelConfiguration {

    @Bean
    @Primary
    public @NonNull ChatModel chatModel(
            @Value("${spring.ai.ollama.base-url:${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}}")
                    @NonNull
                    String baseUrl,
            @Value("${spring.ai.ollama.chat.options.model:${OLLAMA_CHAT_MODEL:qwen3.5:cloud}}") @NonNull
                    String modelName,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey,
            @Value("${spring.ai.ollama.chat.timeout:${OLLAMA_CHAT_TIMEOUT:180s}}") @NonNull Duration timeout) {
        return buildChatModel(baseUrl, modelName, apiKey, timeout);
    }

    @Bean
    @Primary
    public @NonNull StreamingChatModel streamingChatModel(
            @Value("${spring.ai.ollama.base-url:${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}}")
                    @NonNull
                    String baseUrl,
            @Value("${spring.ai.ollama.chat.options.model:${OLLAMA_CHAT_MODEL:qwen3.5:cloud}}") @NonNull
                    String modelName,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey,
            @Value("${spring.ai.ollama.chat.streaming-timeout:${OLLAMA_STREAMING_CHAT_TIMEOUT:${OLLAMA_CHAT_TIMEOUT:180s}}}")
                    @NonNull
                    Duration timeout) {
        return buildChatModel(baseUrl, modelName, apiKey, timeout);
    }

    private static @NonNull OllamaChatModel buildChatModel(
            @NonNull String baseUrl, @NonNull String modelName, @NonNull String apiKey, @NonNull Duration timeout) {
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        if (!apiKey.isBlank()) {
            restClientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaChatOptions.builder()
                        .model(modelName)
                        .temperature(0.2d)
                        .build())
                .build();
    }
}
