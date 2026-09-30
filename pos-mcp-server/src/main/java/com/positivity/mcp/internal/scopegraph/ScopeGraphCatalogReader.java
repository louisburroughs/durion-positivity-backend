package com.positivity.mcp.internal.scopegraph;

import org.jspecify.annotations.NonNull;

/** Reads the tool and screen catalog for a scope-graph build (spec §2.4). */
public interface ScopeGraphCatalogReader {

    /** The whole catalog, one pass per table. */
    @NonNull
    ScopeGraphCatalog read();
}
