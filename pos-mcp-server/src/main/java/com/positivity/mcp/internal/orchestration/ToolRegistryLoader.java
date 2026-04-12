package com.positivity.mcp.internal.orchestration;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Phase 1 stub: returns empty tool lists for all roles.
 * Phase 2 will replace this with DB-backed role-tool mappings.
 */
@Component
public class ToolRegistryLoader {

  /**
   * Returns an empty role-to-tool mapping for Phase 1.
   * Facade tools are injected directly by {@link SessionAgentManager}
   * until DB-backed mappings are implemented in Phase 2.
   */
  public @NonNull Map<String, List<Object>> loadRoleToolMappings() {
    return Map.of();
  }
}
