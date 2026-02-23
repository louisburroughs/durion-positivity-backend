package com.positivity.mcp.internal.dto;

import com.positivity.mcp.internal.entity.LlmApiConfig;
import org.jspecify.annotations.NonNull;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record LlmApiConfigResponse(
        @NonNull UUID id,
        @NonNull String apiId,
        @NonNull String model,
        @NonNull String baseUrl,
        @NonNull String apiKey,
        @NonNull Map<String, String> headers,
        @NonNull OffsetDateTime createdAt,
        @NonNull OffsetDateTime updatedAt
) {
    public static LlmApiConfigResponse from(@NonNull LlmApiConfig entity) {
        return new LlmApiConfigResponse(
                entity.getId(),
                entity.getApiId(),
                entity.getModel(),
                entity.getBaseUrl(),
                entity.getApiKey(),
                entity.getHeaders(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

