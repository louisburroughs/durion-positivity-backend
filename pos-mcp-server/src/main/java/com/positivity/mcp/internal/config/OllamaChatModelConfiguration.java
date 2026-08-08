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
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@Profile("!test")
public class OllamaChatModelConfiguration {

    @Bean
    @Primary
    public @NonNull ChatModel chatModel(
            @Value("${spring.ai.ollama.base-url:${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}}")
                    @NonNull
                    String baseUrl,
            @Value("${spring.ai.ollama.chat.options.model:${OLLAMA_CHAT_MODEL:gpt-oss:120b}}") @NonNull
                    String modelName,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey,
            @Value("${spring.ai.ollama.chat.timeout:${OLLAMA_CHAT_TIMEOUT:180s}}") @NonNull Duration timeout,
            @Value("${OLLAMA_CHAT_THINK:}") @NonNull String think) {
        return buildChatModel(baseUrl, modelName, apiKey, timeout, think);
    }

    @Bean
    @Primary
    public @NonNull StreamingChatModel streamingChatModel(
            @Value("${spring.ai.ollama.base-url:${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}}")
                    @NonNull
                    String baseUrl,
            @Value("${spring.ai.ollama.chat.options.model:${OLLAMA_CHAT_MODEL:gpt-oss:120b}}") @NonNull
                    String modelName,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey,
            @Value(
                            "${spring.ai.ollama.chat.streaming-timeout:${OLLAMA_STREAMING_CHAT_TIMEOUT:${OLLAMA_CHAT_TIMEOUT:180s}}}")
                    @NonNull
                    Duration timeout,
            @Value("${OLLAMA_CHAT_THINK:}") @NonNull String think) {
        return buildChatModel(baseUrl, modelName, apiKey, timeout, think);
    }

    /**
     * Builds the Ollama chat model.
     *
     * <p>Thinking is left to the model default unless {@code OLLAMA_CHAT_THINK} is set. We must not
     * send a {@code think} field unconditionally: Ollama rejects it for models that don't support
     * thinking, and the default executor ({@code gpt-oss:120b}) already returns its answer in the
     * response's {@code content}. Some reasoning models instead route the answer into the
     * {@code thinking} channel; since Spring AI maps only {@code content} to {@code getText()}, that
     * would surface as blank chat output. When configuring such a model, set
     * {@code OLLAMA_CHAT_THINK=false} so the answer is returned in {@code content}. The
     * {@code ChatResponseText} guard on the read side is the backstop for either case.
     */
    private static @NonNull OllamaChatModel buildChatModel(
            @NonNull String baseUrl,
            @NonNull String modelName,
            @NonNull String apiKey,
            @NonNull Duration timeout,
            @NonNull String think) {
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        // OllamaApi talks to the backend over the RestClient for blocking calls and over a separate
        // WebClient for streaming — the API key must be attached to both, or streaming chat 401s
        // against authenticated backends (e.g. ollama.com) while blocking chat works.
        WebClient.Builder webClientBuilder = WebClient.builder();
        if (!apiKey.isBlank()) {
            restClientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            webClientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();

        OllamaChatOptions.Builder optionsBuilder =
                OllamaChatOptions.builder().model(modelName).temperature(0.2d);
        applyThinking(optionsBuilder, think);

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(optionsBuilder.build())
                .build();
    }

    /**
     * Applies an explicit {@code think} override when {@code OLLAMA_CHAT_THINK} is set to a
     * boolean; a blank value leaves the model default untouched (required for non-thinking models,
     * which reject a {@code think} field).
     */
    private static void applyThinking(OllamaChatOptions.@NonNull Builder optionsBuilder, @NonNull String think) {
        String value = think.strip();
        if (value.isEmpty()) {
            return;
        }
        if ("true".equalsIgnoreCase(value)) {
            optionsBuilder.enableThinking();
        } else if ("false".equalsIgnoreCase(value)) {
            optionsBuilder.disableThinking();
        }
    }
}
