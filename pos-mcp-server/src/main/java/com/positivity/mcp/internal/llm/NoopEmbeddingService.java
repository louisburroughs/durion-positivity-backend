package com.positivity.mcp.internal.llm;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnMissingBean(EmbeddingService.class)
public class NoopEmbeddingService implements EmbeddingService {

    @Override
    public @NonNull Mono<float[]> embed(@NonNull String text) {
        return Mono.just(new float[0]);
    }
}
