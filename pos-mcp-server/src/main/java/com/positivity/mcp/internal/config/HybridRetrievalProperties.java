package com.positivity.mcp.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * #784: configuration for hybrid dense + lexical (Postgres FTS) RAG retrieval.
 *
 * <p>Lexical retrieval is <strong>off by default</strong> so the FTS migration and code can ship
 * decoupled from the behavior change; enabling it is a one-line, instantly reversible flip. When
 * disabled, retrieval stays exactly as before (dense + query-expansion fused in insertion order).
 * When enabled, the lexical path joins the source set and fusion switches to Reciprocal Rank Fusion.
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
