package com.positivity.mcp.internal.dto;

import com.positivity.mcp.internal.entity.SystemPrompt;
import org.jspecify.annotations.NonNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SystemPromptResponse(
        @NonNull UUID id,
        @NonNull String name,
        @NonNull String content,
        @NonNull OffsetDateTime createdAt,
        @NonNull OffsetDateTime updatedAt
) {
    public static SystemPromptResponse from(@NonNull SystemPrompt prompt) {
        return new SystemPromptResponse(
                prompt.getId(),
                prompt.getName(),
                prompt.getContent(),
                prompt.getCreatedAt(),
                prompt.getUpdatedAt()
        );
    }
}

