package com.positivity.mcp.internal.config;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.registry")
public record ToolRegistryProperties(
        boolean enabled,
        int semanticTopK,
        int returnedCandidates,
        boolean deterministicFallbackEnabled,
        @NonNull String defaultWorkflowState
) {
    public ToolRegistryProperties {
        if (semanticTopK <= 0) {
            semanticTopK = 10;
        }
        if (returnedCandidates <= 0) {
            returnedCandidates = 5;
        }
        if (defaultWorkflowState == null) {
            defaultWorkflowState = "IDLE";
        }
    }
}
