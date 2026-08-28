package com.positivity.mcp.internal.config;

/**
 * Service-layer observability contract for reporting cached agent counts.
 */
public interface AgentCacheMetrics {

    long getCacheSize();
}
