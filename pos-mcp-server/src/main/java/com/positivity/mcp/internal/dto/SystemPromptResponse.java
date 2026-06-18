package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.mcp.internal.entity.SystemPrompt;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

@Schema(description = "Stored reusable system prompt returned to clients")
public record SystemPromptResponse(
        @Schema(
                        description = "Unique identifier of the stored system prompt",
                        example = "01960003-0000-7000-8000-000000000008",
                        requiredMode = REQUIRED)
                @NonNull @NotNull UUID id,
        @Schema(
                        description = "Human-readable name identifying the system prompt",
                        example = "Workorder Assistant",
                        requiredMode = REQUIRED)
                @NonNull @NotNull String name,
        @Schema(
                        description = "Text content of the system prompt",
                        example = "You are a helpful assistant that creates workorders.",
                        requiredMode = REQUIRED)
                @NonNull @NotNull String content,
        @Schema(
                        description = "Timestamp when the system prompt was created (ISO 8601)",
                        example = "2026-01-15T09:30:00Z",
                        requiredMode = REQUIRED)
                @NonNull @NotNull OffsetDateTime createdAt,
        @Schema(
                        description = "Timestamp when the system prompt was last updated (ISO 8601)",
                        example = "2026-01-16T11:45:00Z",
                        requiredMode = REQUIRED)
                @NonNull @NotNull OffsetDateTime updatedAt) {
    public static SystemPromptResponse from(@NonNull SystemPrompt prompt) {
        return new SystemPromptResponse(
                prompt.getId(), prompt.getName(), prompt.getContent(), prompt.getCreatedAt(), prompt.getUpdatedAt());
    }
}
