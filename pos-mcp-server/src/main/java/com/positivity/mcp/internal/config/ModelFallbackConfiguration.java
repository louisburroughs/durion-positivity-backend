package com.positivity.mcp.internal.config;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "mcp.model.fallback.enabled", havingValue = "true")
public class ModelFallbackConfiguration {

    /**
     * The secondary model shares the primary's temperature and context window (#1683): failover
     * must not silently change determinism or truncate the prompt relative to the primary.
     */
    @Bean("fallbackChatModel")
    public @NonNull ChatModel fallbackChatModel(
            @Value("${OLLAMA_FALLBACK_BASE_URL:${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}}")
                    @NonNull
                    String baseUrl,
            @Value("${mcp.model.fallback.secondary-model-name:gpt-oss:20b}") @NonNull String modelName,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey,
            @Value("${mcp.model.fallback.timeout:180s}") @NonNull Duration timeout,
            @Value("${spring.ai.ollama.chat.options.temperature:${OLLAMA_CHAT_TEMPERATURE:0.0}}") double temperature,
            @Value("${spring.ai.ollama.chat.options.num-ctx:${OLLAMA_NUM_CTX:32768}}") int numCtx) {
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
                        .temperature(temperature)
                        .numCtx(numCtx)
                        .build())
                .build();
    }
}
