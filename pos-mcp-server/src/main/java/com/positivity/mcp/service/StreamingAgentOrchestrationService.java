package com.positivity.mcp.service;

import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Flux;

public interface StreamingAgentOrchestrationService {

    @NonNull
    Flux<String> streamChat(@NonNull CurrentUserContext currentUserContext, @NonNull String message);

    void evict(@NonNull String userId);
}
