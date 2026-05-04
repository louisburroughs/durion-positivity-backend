package com.positivity.mcp.service;

import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;

/**
 * Resolves the primary role for an authenticated MCP caller.
 * Returns the highest-priority ROLE_ authority present, or "ROLE_USER" as a
 * safe default when no known role is present.
 */
public interface McpRoleResolver {

  /**
   * Returns the single highest-priority role for the given authentication.
   * The role is selected by a deterministic priority list. Falls back to
   * "ROLE_USER" if none of the known roles are present.
   */
  @NonNull
  String resolvePrimaryRole(@NonNull Authentication authentication);
}
