package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.ToolRegistryProperties;
import com.positivity.mcp.internal.llm.EmbeddingService;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DefaultToolRegistryResolver implements ToolRegistryResolver {

    private final EmbeddingService embeddingService;
    private final ToolRegistryProperties properties;

    public DefaultToolRegistryResolver(
            @NonNull EmbeddingService embeddingService,
            @NonNull ToolRegistryProperties properties) {
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    @Override
    public @NonNull Mono<List<ToolRegistryCandidate>> resolveCandidates(@NonNull ToolSelectionContext context) {
        if (!properties.enabled()) {
            return Mono.just(List.of());
        }

        return embeddingService.embed(context.userInput())
                .map(ignored -> List.<ToolRegistryCandidate>of())
                .onErrorReturn(List.of());
    }
}
