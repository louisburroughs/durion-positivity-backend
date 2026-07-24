package com.positivity.mcp.internal.orchestration.agent;

import com.positivity.mcp.internal.domain.RagScope;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    public MasterAgentRegistry(@NonNull MasterAgentRegistryFactory registryFactory) {
        this(registryFactory.loadRegistryDefinition());
    }

    private MasterAgentRegistry(MasterAgentRegistryFactory.LoadedMasterAgentRegistry loadedRegistry) {
        this(loadedRegistry.sharedTools(), loadedRegistry.domainAgents());
    }

    public MasterAgentRegistry(@NonNull List<Object> sharedTools, @NonNull List<DomainAgentDefinition> domainAgents) {
        this.sharedTools = List.copyOf(sharedTools);
        this.domainAgents = List.copyOf(domainAgents);
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
        // Gate 2B / #780: legacy role->tool preassignment (mcp_role / mcp_tool_role) retired. Tools are
        // bucketed by domain only; per-request visibility is enforced upstream by permission gating
        // (permissionCodes ∩ mcp_tool_permission ∩ workflow state). Resolution is purely by domain agent.
        List<Object> resolvedTools = new ArrayList<>();
        findDomainAgent(agentName).ifPresent(agent -> resolvedTools.addAll(agent.tools()));
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP master registry resolve-all agentName={} sharedTools={} resolvedTools={}",
                    agentName,
                    sharedTools.stream()
                            .map(tool -> ClassUtils.getUserClass(tool).getSimpleName())
                            .toList(),
                    resolvedTools.stream()
                            .map(tool -> ClassUtils.getUserClass(tool).getSimpleName())
                            .toList());
        }
        return resolvedTools;
    }

    /**
     * Resolves selected tool names against every registered tool (shared + all domain agents),
     * independent of agent/role scoping.
     *
     * <p>Permission gating and semantic scoring already happened upstream in {@code
     * ToolRegistryService} (permissionCodes ∩ {@code mcp_tool_permission} ∩ workflow state), so the
     * name→bean step must search the full registered set. Tools are bucketed by <em>domain</em>
     * (never by role), which is why name resolution is not role-scoped. See Gate 2B / #780: the
     * legacy role→tool preassignment was retired.
     */
    public @NonNull List<Object> resolveToolsByName(@NonNull Collection<String> toolNames) {
        Set<String> selectedNames = new HashSet<>();
        for (String toolName : toolNames) {
            selectedNames.add(toolName.toLowerCase(Locale.ROOT));
        }
        if (selectedNames.isEmpty()) {
            LOGGER.debug("MCP master registry resolve-by-name selectedNames=[] resolvedTools=[]");
            return new ArrayList<>();
        }
        List<Object> availableTools = allRegisteredTools();
        List<Object> resolvedTools = new ArrayList<>();
        for (Object tool : availableTools) {
            if (matchesSelectedTool(tool, selectedNames)
                    && resolvedTools.stream().noneMatch(existing -> sameTool(existing, tool))) {
                resolvedTools.add(tool);
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP master registry resolve-by-name selectedNames={} availableTools={} resolvedTools={}",
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

    private @NonNull List<Object> allRegisteredTools() {
        List<Object> allTools = new ArrayList<>(sharedTools);
        for (DomainAgentDefinition domainAgent : domainAgents) {
            allTools.addAll(domainAgent.tools());
        }
        return allTools;
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
        // ROLE_TECHNICIAN and ROLE_USER are never omitted. Gate 2B / #780: role->tool preassignment is
        // retired, so there are no configured assignments to union in.
        Set<String> roleIdentifiers = new TreeSet<>(SystemPromptDefaults.PRELOADABLE_ROLE_IDENTIFIERS);
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
