package com.positivity.mcp.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * #784: configuration for hybrid dense + lexical (Postgres FTS) RAG retrieval.
 *
 * <p>The application configuration enables lexical retrieval by default because literal identifiers
 * can fall below the dense similarity floor. It remains an instantly reversible feature flag. When
 * disabled, retrieval uses dense + query-expansion fused in insertion order. When enabled, the
 * lexical path joins the source set and fusion switches to Reciprocal Rank Fusion.
 */
@ConfigurationProperties(prefix = "mcp.rag.hybrid")
public record HybridRetrievalProperties(boolean lexicalEnabled, int rrfK, int lexicalMaxResults) {

    public HybridRetrievalProperties {
        if (rrfK <= 0) {
            rrfK = 60; // standard RRF rank constant
        }
        if (lexicalMaxResults <= 0) {
            lexicalMaxResults = 20;
        }
    }
}
