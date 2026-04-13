package com.positivity.mcp.service;

/**
 * Service-layer observability contract for reporting cached agent counts.
 */
public interface AgentCacheMetrics {

  long getCacheSize();
}
