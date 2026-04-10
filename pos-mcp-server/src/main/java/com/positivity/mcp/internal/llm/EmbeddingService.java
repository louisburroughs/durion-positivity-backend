package com.positivity.mcp.internal.llm;

import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Mono;

public interface EmbeddingService {

    @NonNull
    Mono<float[]> embed(@NonNull String text);
}
