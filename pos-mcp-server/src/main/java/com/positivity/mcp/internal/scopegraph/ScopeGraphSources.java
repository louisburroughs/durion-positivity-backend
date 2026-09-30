package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.scopegraph.ScopeGraphGlossarySource.GlossaryTerm;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Everything one scope-graph build reads (ADR-0069 §2, "Source" columns), gathered before the build
 * so the builder itself is a pure function of its inputs.
 *
 * @param ragDocs the effective {@code mcp.rag.preload.docs} of the running profile
 */
public record ScopeGraphSources(
        @NonNull EntityLexicon lexicon,
        @NonNull ScopeGraphCatalog catalog,
        @NonNull List<StaticDocEntry> ragDocs,
        @NonNull OpenApiSchemaIndex schemaIndex,
        @NonNull List<GlossaryTerm> glossaryTerms) {

    public ScopeGraphSources {
        ragDocs = List.copyOf(ragDocs);
        glossaryTerms = List.copyOf(glossaryTerms);
    }
}
