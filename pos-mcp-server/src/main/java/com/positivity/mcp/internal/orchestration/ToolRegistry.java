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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
public class ToolRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistry.class);
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
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tool registry resolve-all role={} resolvedTools={}",
                    role,
                    tools.stream()
                            .map(tool -> ClassUtils.getUserClass(tool).getSimpleName())
                            .toList());
        }
        return new ArrayList<>(tools);
    }

    public @NonNull List<Object> resolveToolsForRole(@NonNull String role, @NonNull Collection<String> toolNames) {
        Set<String> selectedNames = new HashSet<>();
        for (String toolName : toolNames) {
            selectedNames.add(toolName.toLowerCase(Locale.ROOT));
        }
        if (selectedNames.isEmpty()) {
            LOGGER.debug("MCP tool registry resolve-selected role={} selectedNames=[] resolvedTools=[]", role);
            return new ArrayList<>();
        }
        List<Object> availableTools = roleToolMap.getOrDefault(role, List.of());
        List<Object> resolvedTools = availableTools.stream()
                .filter(tool -> matchesSelectedTool(tool, selectedNames))
                .toList();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tool registry resolve-selected role={} selectedNames={} availableTools={} resolvedTools={}",
                    role,
                    new TreeSet<>(selectedNames),
                    availableTools.stream()
                            .map(tool -> ClassUtils.getUserClass(tool).getSimpleName())
                            .toList(),
                    resolvedTools.stream()
                            .map(tool -> ClassUtils.getUserClass(tool).getSimpleName())
                            .toList());
        }
        return resolvedTools;
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
