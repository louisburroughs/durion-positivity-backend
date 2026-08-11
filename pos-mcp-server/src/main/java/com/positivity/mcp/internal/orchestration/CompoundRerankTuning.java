package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.CompoundRerankProperties;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * #1180: installs the bound {@code mcp.rag.rerank.*} tuning into {@link RerankedContentRetriever}
 * at startup. The retriever instances are constructed inside the per-role agent build (they are not
 * Spring-managed beans), so the configuration is applied to the retriever's construction defaults
 * here instead of being threaded through the agent managers. Eager component: runs during context
 * startup, before any role agent is lazily built on the first chat request.
 */
@Component
class CompoundRerankTuning {

    CompoundRerankTuning(@NonNull CompoundRerankProperties properties) {
        RerankedContentRetriever.configureCompoundSlotDefaults(
                properties.compoundSlotsEnabled(), properties.maxSubQueries());
    }
}
