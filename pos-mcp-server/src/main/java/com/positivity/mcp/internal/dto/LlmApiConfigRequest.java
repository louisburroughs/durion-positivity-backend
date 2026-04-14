package com.positivity.mcp.internal.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public record LlmApiConfigRequest(
        @NonNull @NotBlank String apiId,
        @NonNull @NotBlank String model,
        @NonNull @NotBlank String baseUrl,
        @NonNull @NotBlank String apiKey,
        Map<String, String> headers) {}
