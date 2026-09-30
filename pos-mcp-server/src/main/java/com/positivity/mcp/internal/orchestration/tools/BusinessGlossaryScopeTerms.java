package com.positivity.mcp.internal.orchestration.tools;

import com.positivity.mcp.internal.scopegraph.ScopeGraphGlossarySource;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Hands the {@link BusinessGlossary} phrases to the scope graph (ADR-0069 §2, Term row). Only the
 * phrases cross: the definitions stay here, and the graph never restates them (§3.1).
 */
@Component
public class BusinessGlossaryScopeTerms implements ScopeGraphGlossarySource {

    @Override
    public @NonNull List<GlossaryTerm> terms() {
        return BusinessGlossary.definitions().stream()
                .map(definition -> new GlossaryTerm(
                        definition.term(),
                        definition.aliases().stream().sorted().toList()))
                .toList();
    }
}
