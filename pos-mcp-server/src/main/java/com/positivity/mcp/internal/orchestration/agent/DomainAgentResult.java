package com.positivity.mcp.internal.orchestration.agent;

import java.util.List;
import org.jspecify.annotations.NonNull;

public record DomainAgentResult(
        @NonNull String status,
        @NonNull String summary,
        @NonNull List<String> domainFacts,
        @NonNull List<String> toolCalls,
        @NonNull List<String> errors,
        @NonNull List<String> followUpQuestions) {}
