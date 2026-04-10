package com.positivity.mcp.internal.orchestration;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ToolSelectionContext(
        @NonNull String userInput,
        @NonNull String role,
        @NonNull String workflowState,
        @Nullable String intent,
        @Nullable UUID sessionId,
        @Nullable UUID correlationId
) {
}
