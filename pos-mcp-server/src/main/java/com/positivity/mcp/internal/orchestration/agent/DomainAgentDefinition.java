package com.positivity.mcp.internal.orchestration.agent;

import java.util.List;
import org.jspecify.annotations.NonNull;

public record DomainAgentDefinition(
        @NonNull String agentName,
        @NonNull String ragScope,
        @NonNull List<Object> tools) {}
