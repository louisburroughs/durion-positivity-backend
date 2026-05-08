package com.positivity.mcp.internal.orchestration.agent;

import com.positivity.mcp.internal.service.MasterAgentRegistryLoader;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
public final class MasterAgentRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(MasterAgentRegistry.class);

    private final List<Object> sharedTools;
    private final List<DomainAgentDefinition> domainAgents;

    @Autowired
    public MasterAgentRegistry(@NonNull MasterAgentRegistryLoader loader) {
        this(List.of(), toDomainAgents(loader.loadRoleToolMappings()));
    }

    public MasterAgentRegistry(@NonNull List<Object> sharedTools, @NonNull List<DomainAgentDefinition> domainAgents) {
        this.sharedTools = List.copyOf(sharedTools);
        this.domainAgents = List.copyOf(domainAgents);
    }

    public @NonNull List<Object> sharedTools() {
        return sharedTools;
    }

    public @NonNull List<DomainAgentDefinition> domainAgents() {
        return domainAgents;
    }

    public @NonNull Optional<DomainAgentDefinition> findDomainAgent(@NonNull String agentName) {
        return domainAgents.stream().filter(agent -> agent.agentName().equals(agentName)).findFirst();
    }

    public @NonNull List<Object> resolveToolsForRole(@NonNull String role) {
        List<Object> resolvedTools = new ArrayList<>(sharedTools);
        findDomainAgent(role).ifPresent(agent -> resolvedTools.addAll(agent.tools()));
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP master registry resolve-all role={} sharedTools={} resolvedTools={}",
                    role,
                    sharedTools.stream().map(tool -> ClassUtils.getUserClass(tool).getSimpleName()).toList(),
                    resolvedTools.stream().map(tool -> ClassUtils.getUserClass(tool).getSimpleName()).toList());
        }
        return resolvedTools;
    }

    public @NonNull List<Object> resolveToolsForRole(@NonNull String role, @NonNull Collection<String> toolNames) {
        Set<String> selectedNames = new HashSet<>();
        for (String toolName : toolNames) {
            selectedNames.add(toolName.toLowerCase(Locale.ROOT));
        }
        if (selectedNames.isEmpty()) {
            LOGGER.debug("MCP master registry resolve-selected role={} selectedNames=[] resolvedTools=[]", role);
            return new ArrayList<>();
        }
        List<Object> availableTools = resolveToolsForRole(role);
        List<Object> resolvedTools = availableTools.stream()
                .filter(tool -> matchesSelectedTool(tool, selectedNames))
                .toList();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP master registry resolve-selected role={} selectedNames={} availableTools={} resolvedTools={}",
                    role,
                    new TreeSet<>(selectedNames),
                    availableTools.stream().map(tool -> ClassUtils.getUserClass(tool).getSimpleName()).toList(),
                    resolvedTools.stream().map(tool -> ClassUtils.getUserClass(tool).getSimpleName()).toList());
        }
        return resolvedTools;
    }

    public @NonNull Set<String> preloadableRoles() {
        Set<String> roleNames = new TreeSet<>();
        for (DomainAgentDefinition domainAgent : domainAgents) {
            roleNames.add(domainAgent.agentName());
        }
        return roleNames;
    }

    private static boolean matchesSelectedTool(@NonNull Object tool, @NonNull Set<String> selectedNames) {
        String simpleClassName = ClassUtils.getUserClass(tool).getSimpleName();
        String beanStyleClassName = Introspector.decapitalize(simpleClassName);
        return selectedNames.contains(simpleClassName.toLowerCase(Locale.ROOT))
                || selectedNames.contains(beanStyleClassName.toLowerCase(Locale.ROOT));
    }

    private static @NonNull List<DomainAgentDefinition> toDomainAgents(@NonNull Map<String, List<Object>> roleToolMap) {
        List<DomainAgentDefinition> domainAgentDefinitions = new ArrayList<>();
        for (Map.Entry<String, List<Object>> entry : roleToolMap.entrySet()) {
            domainAgentDefinitions.add(new DomainAgentDefinition(
                    entry.getKey(), entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
        }
        return domainAgentDefinitions;
    }
}
