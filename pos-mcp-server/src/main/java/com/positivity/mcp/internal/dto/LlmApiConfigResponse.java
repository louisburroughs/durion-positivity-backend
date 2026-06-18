package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.mcp.internal.entity.LlmApiConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

@Schema(description = "Stored external LLM API configuration returned to clients")
public record LlmApiConfigResponse(
        @Schema(
                        description = "Unique identifier of the stored LLM API configuration",
                        example = "01960003-0000-7000-8000-000000000005",
                        requiredMode = REQUIRED)
                @NonNull @NotNull UUID id,
        @Schema(
                        description = "Stable identifier for the LLM API configuration",
                        example = "openai-gpt4",
                        requiredMode = REQUIRED)
                @NonNull @NotNull String apiId,
        @Schema(
                        description = "Model name invoked on the configured provider",
                        example = "gpt-4o",
                        requiredMode = REQUIRED)
                @NonNull @NotNull String model,
        @Schema(
                        description = "Base URL of the LLM provider API",
                        example = "https://api.openai.com/v1",
                        requiredMode = REQUIRED)
                @NonNull @NotNull String baseUrl,
        @Schema(
                        description = "API key credential used to authenticate with the provider",
                        example = "sk-proj-xxxxxxxxxxxxxxxx",
                        requiredMode = REQUIRED)
                @NonNull @NotNull String apiKey,
        @Schema(
                        description = "Additional HTTP headers sent with each provider request",
                        example = "{\"X-Org-Id\": \"org-01960003\"}",
                        requiredMode = REQUIRED)
                @NonNull @NotNull Map<String, String> headers,
        @Schema(
                        description = "Timestamp when the configuration was created (ISO 8601)",
                        example = "2026-01-15T09:30:00Z",
                        requiredMode = REQUIRED)
                @NonNull @NotNull OffsetDateTime createdAt,
        @Schema(
                        description = "Timestamp when the configuration was last updated (ISO 8601)",
                        example = "2026-01-16T11:45:00Z",
                        requiredMode = REQUIRED)
                @NonNull @NotNull OffsetDateTime updatedAt) {
    public static LlmApiConfigResponse from(@NonNull LlmApiConfig entity) {
        return new LlmApiConfigResponse(
                entity.getId(),
                entity.getApiId(),
                entity.getModel(),
                entity.getBaseUrl(),
                entity.getApiKey(),
                entity.getHeaders(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
