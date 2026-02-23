package com.positivity.mcp.internal.dto;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;

import java.util.Map;

public record LlmApiConfigRequest(
        @NonNull @NotBlank String apiId,
        @NonNull @NotBlank String model,
        @NonNull @NotBlank String baseUrl,
        @NonNull @NotBlank String apiKey,
        Map<String, String> headers
) {
}

