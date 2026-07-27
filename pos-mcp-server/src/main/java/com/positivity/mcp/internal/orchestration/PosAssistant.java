package com.positivity.mcp.internal.orchestration;

import org.jspecify.annotations.NonNull;

public interface PosAssistant {

    @NonNull
    String chat(@NonNull String memoryId, @NonNull String userMessage, @NonNull String userContext);
}
