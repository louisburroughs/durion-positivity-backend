package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;

@Schema(description = "Request to create or update a reusable system prompt")
public record SystemPromptRequest(
        @Schema(
                        description = "Human-readable name identifying the system prompt",
                        example = "Workorder Assistant",
                        requiredMode = REQUIRED)
                @NonNull @NotBlank String name,
        @Schema(
                        description = "Text content of the system prompt",
                        example = "You are a helpful assistant that creates workorders.",
                        requiredMode = REQUIRED)
                @NonNull @NotBlank String content) {}
