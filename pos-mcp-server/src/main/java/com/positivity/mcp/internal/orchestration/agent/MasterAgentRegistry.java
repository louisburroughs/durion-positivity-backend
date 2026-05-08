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
    private final Map<String, List<Object>> roleToolAssignments;

    @Autowired
    public MasterAgentRegistry(@NonNull MasterAgentRegistryLoader loader) {
        this(loader.loadRegistryDefinition());
    }

    private MasterAgentRegistry(MasterAgentRegistryLoader.LoadedMasterAgentRegistry loadedRegistry) {
        this(loadedRegistry.sharedTools(), loadedRegistry.domainAgents(), loadedRegistry.roleToolAssignments());
    }

    public MasterAgentRegistry(@NonNull List<Object> sharedTools, @NonNull List<DomainAgentDefinition> domainAgents) {
        this(sharedTools, domainAgents, Map.of());
    }

    public MasterAgentRegistry(
            @NonNull List<Object> sharedTools,
            @NonNull List<DomainAgentDefinition> domainAgents,
            @NonNull Map<String, List<Object>> roleToolAssignments) {
        this.sharedTools = List.copyOf(sharedTools);
        this.domainAgents = List.copyOf(domainAgents);
        this.roleToolAssignments = Map.copyOf(roleToolAssignments);
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

    public @NonNull List<Object> resolveToolsForDomainAgent(@NonNull String agentName) {
        List<Object> resolvedTools = new ArrayList<>(sharedTools);
        List<Object> assignedTools = roleToolAssignments.get(agentName);
        if (assignedTools != null) {
            resolvedTools.addAll(assignedTools);
        } else {
            findDomainAgent(agentName).ifPresent(agent -> resolvedTools.addAll(agent.tools()));
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP master registry resolve-all agentName={} sharedTools={} resolvedTools={}",
                    agentName,
                    sharedTools.stream().map(tool -> ClassUtils.getUserClass(tool).getSimpleName()).toList(),
                    resolvedTools.stream().map(tool -> ClassUtils.getUserClass(tool).getSimpleName()).toList());
        }
        return resolvedTools;
    }

    public @NonNull List<Object> resolveToolsForDomainAgent(
            @NonNull String agentName, @NonNull Collection<String> toolNames) {
        Set<String> selectedNames = new HashSet<>();
        for (String toolName : toolNames) {
            selectedNames.add(toolName.toLowerCase(Locale.ROOT));
        }
        if (selectedNames.isEmpty()) {
            LOGGER.debug(
                    "MCP master registry resolve-selected agentName={} selectedNames=[] resolvedTools=[]", agentName);
            return new ArrayList<>();
        }
        List<Object> availableTools = resolveToolsForDomainAgent(agentName);
        List<Object> resolvedTools = availableTools.stream()
                .filter(tool -> matchesSelectedTool(tool, selectedNames))
                .toList();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP master registry resolve-selected agentName={} selectedNames={} availableTools={} resolvedTools={}",
                    agentName,
                    new TreeSet<>(selectedNames),
                    availableTools.stream().map(tool -> ClassUtils.getUserClass(tool).getSimpleName()).toList(),
                    resolvedTools.stream().map(tool -> ClassUtils.getUserClass(tool).getSimpleName()).toList());
        }
        return resolvedTools;
    }

    public @NonNull Set<String> preloadableDomainAgents() {
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
}
