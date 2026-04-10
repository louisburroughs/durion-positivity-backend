package com.positivity.mcp.internal.orchestration;

import java.util.List;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Mono;

public interface ToolRegistryResolver {

    @NonNull
    Mono<List<ToolRegistryCandidate>> resolveCandidates(@NonNull ToolSelectionContext context);
}
