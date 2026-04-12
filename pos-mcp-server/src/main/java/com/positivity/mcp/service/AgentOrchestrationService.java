package com.positivity.mcp.service;

import org.jspecify.annotations.NonNull;

/**
 * Public API for per-user LangChain4j agent session management.
 * Implementations maintain a per-user agent cache with role-aware tool sets.
 */
public interface AgentOrchestrationService {

  /**
   * Returns or creates a PosAssistant proxy for the given user and role.
   * Multiple chat turns for the same user reuse the same agent instance.
   */
  @NonNull
  String chat(@NonNull String userId, @NonNull String role, @NonNull String message);

  /**
   * Evicts the cached agent for the given user.
   * Call on role changes or explicit logout.
   */
  void evict(@NonNull String userId);
}