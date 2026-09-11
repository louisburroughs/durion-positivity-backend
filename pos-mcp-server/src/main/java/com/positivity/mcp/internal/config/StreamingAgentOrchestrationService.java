package com.positivity.mcp.internal.config;

import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Flux;

public interface StreamingAgentOrchestrationService {

    @NonNull
    Flux<String> streamChat(@NonNull CurrentUserContext currentUserContext, @NonNull String message);

    /** Evicts the conversation state and rate counter of {@code username} within the bound tenant. */
    void evict(@NonNull String username);
}
