package com.positivity.mcp.internal.orchestration.agent;

import com.positivity.mcp.internal.domain.RagScope;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import com.positivity.mcp.internal.service.ToolRegistryRoleMapper;
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
    public MasterAgentRegistry(@NonNull MasterAgentRegistryFactory registryFactory) {
        this(registryFactory.loadRegistryDefinition());
    }

    private MasterAgentRegistry(MasterAgentRegistryFactory.LoadedMasterAgentRegistry loadedRegistry) {
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

    public @NonNull List<Object> resolveMasterTools() {
        return new ArrayList<>(sharedTools);
    }

    public @NonNull List<DomainAgentDefinition> domainAgents() {
        return domainAgents;
    }

    public @NonNull Optional<DomainAgentDefinition> findDomainAgent(@NonNull String agentName) {
        return domainAgents.stream()
                .filter(agent -> agent.agentName().equals(agentName))
                .findFirst();
    }

    public @NonNull List<Object> resolveDomainTools(@NonNull String agentName) {
        List<Object> resolvedTools = new ArrayList<>();
        String normalizedAgentName = ToolRegistryRoleMapper.normalize(agentName);
        List<Object> assignedTools = roleToolAssignments.get(normalizedAgentName);
        if (assignedTools != null) {
            resolvedTools.addAll(assignedTools);
        } else {
            findDomainAgent(agentName).ifPresent(agent -> resolvedTools.addAll(agent.tools()));
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP master registry resolve-all agentName={} sharedTools={} resolvedTools={}",
                    normalizedAgentName,
                    sharedTools.stream()
                            .map(tool -> ClassUtils.getUserClass(tool).getSimpleName())
                            .toList(),
                    resolvedTools.stream()
                            .map(tool -> ClassUtils.getUserClass(tool).getSimpleName())
                            .toList());
        }
        return resolvedTools;
    }

    public @NonNull List<Object> resolveDomainTools(@NonNull String agentName, @NonNull Collection<String> toolNames) {
        Set<String> selectedNames = new HashSet<>();
        for (String toolName : toolNames) {
            selectedNames.add(toolName.toLowerCase(Locale.ROOT));
        }
        if (selectedNames.isEmpty()) {
            LOGGER.debug(
                    "MCP master registry resolve-selected agentName={} selectedNames=[] resolvedTools=[]", agentName);
            return new ArrayList<>();
        }
        List<Object> availableTools = resolveDomainTools(agentName);
        List<Object> resolvedTools = availableTools.stream()
                .filter(tool -> matchesSelectedTool(tool, selectedNames))
                .toList();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP master registry resolve-selected agentName={} selectedNames={} availableTools={} resolvedTools={}",
                    agentName,
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

    public @NonNull String resolveRagScopeForTools(@NonNull Collection<Object> tools) {
        if (tools.stream().anyMatch(this::isSharedTool)) {
            return RagScope.MASTER;
        }
        Set<String> ragScopes = new TreeSet<>();
        for (Object tool : tools) {
            Optional<DomainAgentDefinition> domainAgent = findDomainAgentForTool(tool);
            if (domainAgent.isEmpty()) {
                return RagScope.MASTER;
            }
            ragScopes.add(domainAgent.orElseThrow().ragScope());
        }
        if (ragScopes.size() == 1) {
            return ragScopes.iterator().next();
        }
        return RagScope.MASTER;
    }

    public @NonNull Set<String> preloadableDomainAgents() {
        Set<String> roleNames = new TreeSet<>();
        for (DomainAgentDefinition domainAgent : domainAgents) {
            roleNames.add(domainAgent.agentName());
        }
        return roleNames;
    }

    public @NonNull Set<String> preloadableRoleIdentifiers() {
        // Gate 2A / #639: always cover the canonical role set (MCP_ROLE_PRIORITY + ROLE_USER) so
        // ROLE_TECHNICIAN and ROLE_USER are never omitted, unioned with any configured assignments.
        Set<String> roleIdentifiers = new TreeSet<>(SystemPromptDefaults.PRELOADABLE_ROLE_IDENTIFIERS);
        roleIdentifiers.addAll(roleToolAssignments.keySet());
        if (!roleIdentifiers.isEmpty()) {
            return roleIdentifiers;
        }
        return preloadableDomainAgents();
    }

    private static boolean matchesSelectedTool(@NonNull Object tool, @NonNull Set<String> selectedNames) {
        String simpleClassName = ClassUtils.getUserClass(tool).getSimpleName();
        String beanStyleClassName = Introspector.decapitalize(simpleClassName);
        return selectedNames.contains(simpleClassName.toLowerCase(Locale.ROOT))
                || selectedNames.contains(beanStyleClassName.toLowerCase(Locale.ROOT));
    }

    private @NonNull Optional<DomainAgentDefinition> findDomainAgentForTool(@NonNull Object selectedTool) {
        return domainAgents.stream()
                .filter(agent -> agent.tools().stream().anyMatch(tool -> sameTool(tool, selectedTool)))
                .findFirst();
    }

    private boolean isSharedTool(@NonNull Object selectedTool) {
        return sharedTools.stream().anyMatch(tool -> sameTool(tool, selectedTool));
    }

    private static boolean sameTool(@NonNull Object left, @NonNull Object right) {
        return ClassUtils.getUserClass(left).equals(ClassUtils.getUserClass(right));
    }
}
