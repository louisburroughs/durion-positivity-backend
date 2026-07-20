package com.positivity.mcp.internal.orchestration;

import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Flux;

public interface StreamingPosAssistant {

    @NonNull
    Flux<String> chat(@NonNull String memoryId, @NonNull String userMessage, @NonNull String userContext);
}
