package com.positivity.mcp.internal.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class OllamaConfigurationDiagnosticRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaConfigurationDiagnosticRunner.class);

    private final String chatBaseUrl;
    private final String chatModel;
    private final String embeddingBaseUrl;
    private final String embeddingModel;
    private final String fallbackBaseUrl;
    private final String fallbackModel;
    private final boolean fallbackEnabled;
    private final String apiKey;

    public OllamaConfigurationDiagnosticRunner(
            @Value("${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}") @NonNull String chatBaseUrl,
            @Value("${OLLAMA_CHAT_MODEL:qwen3.5:cloud}") @NonNull String chatModel,
            @Value("${OLLAMA_EMBEDDING_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}") @NonNull
                    String embeddingBaseUrl,
            @Value("${OLLAMA_EMBEDDING_MODEL:qwen3-embedding}") @NonNull String embeddingModel,
            @Value("${OLLAMA_FALLBACK_BASE_URL:${OLLAMA_CHAT_BASE_URL:${OLLAMA_BASE_URL:http://localhost:11434}}}")
                    @NonNull
                    String fallbackBaseUrl,
            @Value("${OLLAMA_FALLBACK_MODEL:gpt-oss:120b}") @NonNull String fallbackModel,
            @Value("${MCP_MODEL_FALLBACK_ENABLED:false}") boolean fallbackEnabled,
            @Value("${OLLAMA_API_KEY:}") @NonNull String apiKey) {
        this.chatBaseUrl = chatBaseUrl;
        this.chatModel = chatModel;
        this.embeddingBaseUrl = embeddingBaseUrl;
        this.embeddingModel = embeddingModel;
        this.fallbackBaseUrl = fallbackBaseUrl;
        this.fallbackModel = fallbackModel;
        this.fallbackEnabled = fallbackEnabled;
        this.apiKey = apiKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOGGER.info(
                "Ollama configuration chatBaseUrl={} chatModel={} chatCloud={} embeddingBaseUrl={} embeddingModel={} embeddingCloud={} fallbackEnabled={} fallbackBaseUrl={} fallbackModel={} fallbackCloud={} apiKeyPresent={}",
                chatBaseUrl,
                chatModel,
                isCloud(chatBaseUrl),
                embeddingBaseUrl,
                embeddingModel,
                isCloud(embeddingBaseUrl),
                fallbackEnabled,
                fallbackBaseUrl,
                fallbackModel,
                isCloud(fallbackBaseUrl),
                !apiKey.isBlank());
    }

    private static boolean isCloud(@NonNull String baseUrl) {
        return baseUrl.contains("ollama.com");
    }
}
