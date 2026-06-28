package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.jspecify.annotations.NonNull;

@Schema(description = "Request to create or update an external LLM API configuration")
public record LlmApiConfigRequest(
        @Schema(
                description = "Stable identifier for the LLM API configuration",
                example = "openai-gpt4",
                requiredMode = REQUIRED)
        @NonNull
        @NotBlank
        String apiId,

        @Schema(
                description = "Model name to invoke on the configured provider",
                example = "gpt-4o",
                requiredMode = REQUIRED)
        @NonNull
        @NotBlank
        String model,

        @Schema(
                description = "Base URL of the LLM provider API",
                example = "https://api.openai.com/v1",
                requiredMode = REQUIRED)
        @NonNull
        @NotBlank
        String baseUrl,

        @Schema(
                description = "API key credential used to authenticate with the provider",
                example = "sk-proj-xxxxxxxxxxxxxxxx",
                requiredMode = REQUIRED)
        @NonNull
        @NotBlank
        String apiKey,

        @Schema(
                description = "Additional HTTP headers to send with each provider request",
                example = "{\"X-Org-Id\": \"org-01960003\"}",
                requiredMode = NOT_REQUIRED)
        Map<String, String> headers) {}
