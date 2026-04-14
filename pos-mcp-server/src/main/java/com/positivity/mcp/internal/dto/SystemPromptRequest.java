package com.positivity.mcp.internal.dto;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;

public record SystemPromptRequest(
        @NonNull @NotBlank String name, @NonNull @NotBlank String content) {}
