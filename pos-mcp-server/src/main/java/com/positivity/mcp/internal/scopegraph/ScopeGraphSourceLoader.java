package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Gathers the sources of one scope-graph build (ADR-0069 §2): the lexicon from the classpath, the
 * catalog from the database, the RAG documents from the effective configuration of the running
 * profile, the schema index from the last discovery, and the glossary phrases.
 *
 * <p>Nothing is read at construction. {@link #load()} is only called by a build, and no build runs
 * with {@code mcp.scope-graph.mode: off}.
 */
@Component
public class ScopeGraphSourceLoader {

    /** Optional: the JDBC reader is conditioned like the tool-metadata repository. */
    private final ObjectProvider<ScopeGraphCatalogReader> catalogReader;

    private final StaticRagPreloadProperties ragPreloadProperties;
    private final OpenApiSchemaIndexHolder schemaIndexHolder;
    private final ObjectProvider<ScopeGraphGlossarySource> glossarySource;

    public ScopeGraphSourceLoader(
            @NonNull ObjectProvider<ScopeGraphCatalogReader> catalogReader,
            @NonNull StaticRagPreloadProperties ragPreloadProperties,
            @NonNull OpenApiSchemaIndexHolder schemaIndexHolder,
            @NonNull ObjectProvider<ScopeGraphGlossarySource> glossarySource) {
        this.catalogReader = catalogReader;
        this.ragPreloadProperties = ragPreloadProperties;
        this.schemaIndexHolder = schemaIndexHolder;
        this.glossarySource = glossarySource;
    }

    /**
     * Reads every source. Throws when the lexicon is structurally broken or no catalog reader exists
     * in this profile; the holder then keeps its previous snapshot (spec §2.5).
     */
    public @NonNull ScopeGraphSources load() {
        ScopeGraphCatalogReader reader = catalogReader.getIfAvailable();
        if (reader == null) {
            throw new IllegalStateException("No ScopeGraphCatalogReader is available in this profile");
        }
        ScopeGraphGlossarySource glossary = glossarySource.getIfAvailable();
        return new ScopeGraphSources(
                EntityLexiconLoader.loadDefault(),
                reader.read(),
                ragPreloadProperties.docs(),
                schemaIndexHolder.current(),
                glossary == null ? List.of() : glossary.terms());
    }
}
