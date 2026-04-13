package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.service.ToolRegistryLoader;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ToolRegistry}: role resolution and mutable-copy
 * guarantee.
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

  @Mock
  private ToolRegistryLoader loader;

  @Test
  @DisplayName("resolveToolsForRole with known role returns the configured tools")
  void resolveToolsForRole_withKnownRole_returnsTools() {
    Object tool = new Object();
    when(loader.loadRoleToolMappings()).thenReturn(Map.of("TECH", List.of(tool)));
    ToolRegistry registry = new ToolRegistry(loader);

    List<Object> result = registry.resolveToolsForRole("TECH");

    assertThat(result).containsExactly(tool);
  }

  @Test
  @DisplayName("resolveToolsForRole with unknown role returns empty list")
  void resolveToolsForRole_withUnknownRole_returnsEmpty() {
    when(loader.loadRoleToolMappings()).thenReturn(Map.of());
    ToolRegistry registry = new ToolRegistry(loader);

    List<Object> result = registry.resolveToolsForRole("UNKNOWN_ROLE");

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("resolveToolsForRole returns a mutable copy — mutations do not affect registry state")
  void resolveToolsForRole_returnsMutableCopy() {
    Object tool = new Object();
    when(loader.loadRoleToolMappings()).thenReturn(Map.of("TECH", List.of(tool)));
    ToolRegistry registry = new ToolRegistry(loader);

    List<Object> firstCall = registry.resolveToolsForRole("TECH");
    firstCall.add(new Object()); // mutate the returned copy

    List<Object> secondCall = registry.resolveToolsForRole("TECH");

    assertThat(secondCall).hasSize(1); // registry still holds only the original tool
  }
}
