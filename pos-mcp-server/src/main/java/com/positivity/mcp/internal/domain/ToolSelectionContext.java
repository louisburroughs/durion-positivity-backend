package com.positivity.mcp.internal.domain;

import org.jspecify.annotations.NonNull;

public record ToolSelectionContext(
        @NonNull String userInput,
        @NonNull String role,
        @NonNull String workflowState) {}
