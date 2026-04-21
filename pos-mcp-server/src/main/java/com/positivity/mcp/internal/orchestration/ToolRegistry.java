package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.service.ToolRegistryLoader;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

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

    public @NonNull List<Object> resolveToolsForRole(@NonNull String role, @NonNull Collection<String> toolNames) {
        Set<String> selectedNames = new HashSet<>();
        for (String toolName : toolNames) {
            selectedNames.add(toolName.toLowerCase(Locale.ROOT));
        }
        if (selectedNames.isEmpty()) {
            return new ArrayList<>();
        }
        return roleToolMap.getOrDefault(role, List.of()).stream()
                .filter(tool -> matchesSelectedTool(tool, selectedNames))
                .toList();
    }

    private static boolean matchesSelectedTool(@NonNull Object tool, @NonNull Set<String> selectedNames) {
        String simpleClassName = ClassUtils.getUserClass(tool).getSimpleName();
        String beanStyleClassName = Introspector.decapitalize(simpleClassName);
        return selectedNames.contains(simpleClassName.toLowerCase(Locale.ROOT))
                || selectedNames.contains(beanStyleClassName.toLowerCase(Locale.ROOT));
    }

    public @NonNull Set<String> preloadableRoles() {
        return new TreeSet<>(roleToolMap.keySet());
    }
}
