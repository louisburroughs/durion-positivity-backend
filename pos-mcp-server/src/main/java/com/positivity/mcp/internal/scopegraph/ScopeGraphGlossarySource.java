package com.positivity.mcp.internal.scopegraph;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * The glossary phrases that become {@code Term} nodes (ADR-0069 §2, Term row). An interface so this
 * package does not depend on the orchestration package that owns {@code BusinessGlossary}: the
 * orchestration and service packages both depend on the scope graph, and the reverse edge would be a
 * package cycle.
 */
@FunctionalInterface
public interface ScopeGraphGlossarySource {

    /** One decided glossary term and the other wordings that resolve to it. */
    record GlossaryTerm(@NonNull String term, @NonNull List<String> aliases) {
        public GlossaryTerm {
            aliases = List.copyOf(aliases);
        }
    }

    @NonNull
    List<GlossaryTerm> terms();
}
