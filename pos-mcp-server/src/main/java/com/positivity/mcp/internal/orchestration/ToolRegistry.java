package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.service.ToolRegistryLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final Map<String, List<Object>> roleToolMap;

    /**
     * Constructed at startup from DB-backed role-tool mappings.
     * Each tool is a Java object with @Tool-annotated methods.
     */
    public ToolRegistry(@NonNull ToolRegistryLoader loader) {
        this.roleToolMap = loader.loadRoleToolMappings();
    }

    /**
     * Returns the tool instances visible to the given role.
     * Returns a mutable copy so callers can append session-specific tools.
     */
    public @NonNull List<Object> resolveToolsForRole(@NonNull String role) {
        List<Object> tools = roleToolMap.getOrDefault(role, List.of());
        return new ArrayList<>(tools);
    }
}
