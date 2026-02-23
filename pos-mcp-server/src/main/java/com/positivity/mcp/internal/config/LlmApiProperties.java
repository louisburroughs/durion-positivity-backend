package com.positivity.mcp.internal.config;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ConfigurationProperties(prefix = "mcp.llm")
public record LlmApiProperties(
        @NonNull List<LlmApi> apis
) {
    public LlmApiProperties {
        if (apis == null) {
            apis = List.of();
        }
    }

    public record LlmApi(
            @NonNull String id,
            @NonNull String model,
            @NonNull String baseUrl,
            String apiKey,
            Map<String, String> headers
    ) {
        public LlmApi {
            Objects.requireNonNull(id, "id is required");
            Objects.requireNonNull(model, "model is required");
            Objects.requireNonNull(baseUrl, "baseUrl is required");
            if (headers == null) {
                headers = Collections.emptyMap();
            }
        }
    }
}

