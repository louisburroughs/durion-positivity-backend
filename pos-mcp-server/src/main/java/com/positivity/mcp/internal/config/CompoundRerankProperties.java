package com.positivity.mcp.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * #1180: configuration for compound-question slot reservation in the final rerank cut.
 *
 * <p>A compound question carries two (or more) information needs in one query — e.g. "what does an
 * invoice number look like and what permission is needed to read an invoice". Scoring every fused
 * candidate against the whole query lets chunks serving the dominant need push the other need's
 * best chunk out of the top-K context window. When enabled, the re-ranker splits such queries into
 * sub-queries and guarantees each sub-query's best-matching chunk a slot in the final top-K.
 * Single-need queries never split, so their results are unchanged; the flag remains instantly
 * reversible for operators.
 *
 * @param compoundSlotsEnabled whether the reserved-slot cut is active (default {@code true})
 * @param maxSubQueries cap on detected sub-queries eligible for a reserved slot (default 3)
 */
@ConfigurationProperties(prefix = "mcp.rag.rerank")
public record CompoundRerankProperties(
        @DefaultValue("true") boolean compoundSlotsEnabled,
        @DefaultValue("3") int maxSubQueries) {

    public CompoundRerankProperties {
        if (maxSubQueries <= 0) {
            maxSubQueries = 3;
        }
    }
}
