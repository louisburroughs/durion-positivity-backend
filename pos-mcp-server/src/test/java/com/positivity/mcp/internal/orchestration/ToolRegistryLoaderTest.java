package com.positivity.mcp.internal.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ToolRegistryLoader} Phase 1 stub behaviour.
 */
class ToolRegistryLoaderTest {

  private final ToolRegistryLoader loader = new ToolRegistryLoader();

  @Test
  @DisplayName("loadRoleToolMappings returns empty map for Phase 1 stub")
  void loadRoleToolMappings_returnsEmptyMap() {
    var result = loader.loadRoleToolMappings();

    assertThat(result).isEmpty();
  }
}
