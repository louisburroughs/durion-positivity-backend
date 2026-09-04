package com.positivity.mcp.internal.config;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaChatModelConfiguration.class);

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
            @Value("${OLLAMA_CHAT_THINK:}") @NonNull String think,
            @Value("${spring.ai.ollama.chat.options.temperature:${OLLAMA_CHAT_TEMPERATURE:0.0}}") double temperature,
            @Value("${spring.ai.ollama.chat.options.num-ctx:${OLLAMA_NUM_CTX:32768}}") int numCtx) {
        return buildChatModel(baseUrl, modelName, apiKey, timeout, think, temperature, numCtx);
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
            @Value("${OLLAMA_CHAT_THINK:}") @NonNull String think,
            @Value("${spring.ai.ollama.chat.options.temperature:${OLLAMA_CHAT_TEMPERATURE:0.0}}") double temperature,
            @Value("${spring.ai.ollama.chat.options.num-ctx:${OLLAMA_NUM_CTX:32768}}") int numCtx) {
        return buildChatModel(baseUrl, modelName, apiKey, timeout, think, temperature, numCtx);
    }

    /**
     * Builds the Ollama chat model.
     *
     * <p><strong>{@code numCtx} is always sent explicitly (#1683).</strong> Ollama silently drops
     * the front of the context — the system prompt — once the assembled prompt exceeds the window,
     * with no error and no log line. One analytics turn (layered system prompt + tool schemas + RAG
     * snippets + chat memory + tool results) is well past 4096, so an inherited window would
     * truncate exactly the layer prompt tuning edits.
     *
     * <p>What "not sent" inherits depends on the backend: a self-hosted daemon applies its own
     * {@code OLLAMA_CONTEXT_LENGTH} (4096 unless raised), while the hosted ollama.com backend the
     * alpha chat base-url points at applies a per-model default we neither set nor can read back.
     * Sending it explicitly is what makes the window ours in both cases — though it is a request,
     * not a guarantee: a backend may still cap it below what we ask for, which is why the
     * verification procedure in {@code docs/gate-verification-runbook.md} exists.
     *
     * <p>Temperature defaults to 0: the analytics workload is graded at n=1, so sampling only adds
     * run-to-run variance to results we compare across builds.
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
            @NonNull String think,
            double temperature,
            int numCtx) {
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

        OllamaChatOptions.Builder optionsBuilder = OllamaChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .numCtx(numCtx);
        applyThinking(optionsBuilder, think);

        // Logged at INFO so the effective context window is verifiable from a deployed instance's
        // startup log: compare it against the response's prompt_eval_count to confirm or rule out
        // prompt truncation without shell access to the Ollama host (#1683).
        LOGGER.info(
                "MCP Ollama chat model configured: model={} temperature={} numCtx={} timeoutMs={}",
                modelName,
                temperature,
                numCtx,
                timeoutMillis);

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
