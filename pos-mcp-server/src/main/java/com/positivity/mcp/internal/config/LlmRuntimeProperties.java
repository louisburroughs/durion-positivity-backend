package com.positivity.mcp.internal.config;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.llm.runtime")
public record LlmRuntimeProperties(
        @NonNull String provider,
        @NonNull String defaultApiId,
        @NonNull String embeddingApiId,
        int maxToolIterations,
        boolean degradeOnProviderFailure,
        @NonNull Duration connectTimeout,
        @NonNull Duration readTimeout,
        @NonNull String systemPrompt
) {
    public LlmRuntimeProperties {
        if (provider == null) {
            provider = "disabled";
        }
        if (defaultApiId == null) {
            defaultApiId = "";
        }
        if (embeddingApiId == null) {
            embeddingApiId = "";
        }
        if (maxToolIterations <= 0) {
            maxToolIterations = 4;
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(30);
        }
        if (systemPrompt == null) {
            systemPrompt = "You are the Durion POS MCP orchestrator. Use only the provided tools.";
        }
    }
}
