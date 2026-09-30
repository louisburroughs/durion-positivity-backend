package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.scopegraph.OpenApiSchemaIndex.DomainIndex;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Holds the {@link OpenApiSchemaIndex} captured by tool discovery (spec §2.2). In memory only: the
 * specs can always be fetched again.
 *
 * <p>Each successfully fetched service spec replaces the entry of its own domain. A domain whose
 * fetch failed this cycle is simply not replaced, so it keeps the entry of the last cycle that saw
 * it, the same rule as the per-prefix prune (#1632): absent is unseen, not removed.
 */
@Component
public class OpenApiSchemaIndexHolder {

    private final Map<String, DomainIndex> byDomain = new ConcurrentHashMap<>();

    /** Replaces the index of {@code index.domain()}. */
    public void put(@NonNull DomainIndex index) {
        byDomain.put(index.domain(), index);
    }

    /** An immutable view of everything captured so far; empty until discovery has run. */
    public @NonNull OpenApiSchemaIndex current() {
        return byDomain.isEmpty() ? OpenApiSchemaIndex.empty() : new OpenApiSchemaIndex(byDomain.values());
    }
}
