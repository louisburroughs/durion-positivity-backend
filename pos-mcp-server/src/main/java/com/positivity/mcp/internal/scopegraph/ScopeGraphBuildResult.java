package com.positivity.mcp.internal.scopegraph;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** The outcome of one build: the graph, always, plus whatever the validation found. */
public record ScopeGraphBuildResult(
        @NonNull ScopeGraph graph, @NonNull List<ScopeGraphFinding> findings) {

    public ScopeGraphBuildResult {
        findings = List.copyOf(findings);
    }

    /** The findings that fail the module test (ADR-0069 §3). */
    public @NonNull List<ScopeGraphFinding> strictFindings() {
        return findings.stream().filter(ScopeGraphFinding::strict).toList();
    }

    public long count(ScopeGraphFinding.@NonNull Kind kind) {
        return findings.stream().filter(finding -> finding.kind() == kind).count();
    }

    /** Discovered operations attached to their domain only (ADR-0069 §3). */
    public int unmappedTools() {
        return (int) count(ScopeGraphFinding.Kind.DISCOVERED_TOOL_UNMAPPED);
    }
}
