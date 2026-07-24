package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class MasterAgentRegistryLoader {

    private static final Logger log = LoggerFactory.getLogger(MasterAgentRegistryLoader.class);
    // #778: default to preloading the union of tools across ALL workflow states so non-IDLE tool sets
    // have their beans available in the registry. Override with a CSV of state names if needed.
    private static final String DEFAULT_PRELOAD_WORKFLOW_STATE = "ALL";
    private static final Set<String> MASTER_DOMAINS = Set.of("master", "shared");

    private final ToolMetadataRepository repository;
    private final ApplicationContext applicationContext;
    private final Set<String> preloadStates;

    MasterAgentRegistryLoader(
            @NonNull ToolMetadataRepository repository, @NonNull ApplicationContext applicationContext) {
        this(repository, applicationContext, DEFAULT_PRELOAD_WORKFLOW_STATE);
    }

    @Autowired
    public MasterAgentRegistryLoader(
            @NonNull ToolMetadataRepository repository,
            @NonNull ApplicationContext applicationContext,
            @Value("${mcp.agent.preload-workflow-state:ALL}") @NonNull String preloadWorkflowState) {
        this.repository = repository;
        this.applicationContext = applicationContext;
        this.preloadStates = parsePreloadStates(preloadWorkflowState);
    }

    public @NonNull LoadedMasterAgentRegistry loadRegistryDefinition() {
        // #778: preload the union of tools enabled across the configured workflow states (default: all),
        // deduped by tool name, so a session in a non-IDLE state (CREATING_PO, PROCESSING_RETURN, ...)
        // finds its gated tool beans in the registry once workflow state is threaded into selection.
        Map<String, ToolMetadata> uniqueByName = new LinkedHashMap<>();
        for (String state : preloadStates) {
            for (ToolMetadata tool : repository.findEnabledByWorkflow(state)) {
                uniqueByName.putIfAbsent(tool.name(), tool);
            }
        }
        List<ToolMetadata> tools = new ArrayList<>(uniqueByName.values());
        if (tools.isEmpty()) {
            log.warn(
                    "No workflow-scoped tools found for workflowStates={}; master agent registry will be empty",
                    preloadStates);
        }
        List<Object> sharedTools = new ArrayList<>();
        Map<String, List<Object>> domainScopedTools = new TreeMap<>();
        for (ToolMetadata tool : tools) {
            String domain = normalizeDomain(tool.domain());
            Object bean = loadToolBean(tool);
            if (bean != null) {
                if (MASTER_DOMAINS.contains(domain)) {
                    sharedTools.add(bean);
                } else {
                    domainScopedTools
                            .computeIfAbsent(domain, ignored -> new ArrayList<>())
                            .add(bean);
                }
            }
        }
        // Gate 2B / #780: the legacy role->tool preassignment (mcp_role / mcp_tool_role) is retired.
        // Tool visibility is now fully determined by permission gating at request time
        // (permissionCodes ∩ mcp_tool_permission ∩ workflow state — ToolMetadataRepository
        // .findTopKByEmbeddingForPermissions), so role-scoped preassignment is no longer built.
        return new LoadedMasterAgentRegistry(List.copyOf(sharedTools), immutableCopy(domainScopedTools));
    }

    private Object loadToolBean(@NonNull ToolMetadata tool) {
        // Openapi-discovered rows have no handler_bean (they execute via OpenApiToolProvider, not a
        // bean); they are excluded by the repository query, but guard defensively so a null never
        // reaches getBean (which throws "'name' must not be null" and fails context init).
        if (tool.handlerBean() == null || tool.handlerBean().isBlank()) {
            return null;
        }
        try {
            return applicationContext.getBean(tool.handlerBean());
        } catch (NoSuchBeanDefinitionException e) {
            log.warn(
                    "Tool bean '{}' not found for tool '{}' in domain '{}', skipping: {}",
                    tool.handlerBean(),
                    tool.name(),
                    tool.domain(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Parses the {@code mcp.agent.preload-workflow-state} config into the set of workflow states to
     * preload. {@code ALL}/{@code *}/blank preloads every {@link WorkflowState}; otherwise a CSV of
     * state names is honored (unknown names are logged and skipped). Falls back to the default state
     * if nothing valid is supplied.
     */
    private static @NonNull Set<String> parsePreloadStates(@NonNull String config) {
        String normalized = config.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.equals("ALL") || normalized.equals("*")) {
            return Arrays.stream(WorkflowState.values())
                    .map(Enum::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        Set<String> states = new LinkedHashSet<>();
        for (String token : normalized.split(",")) {
            String name = token.trim();
            if (name.isBlank()) {
                continue;
            }
            try {
                states.add(WorkflowState.valueOf(name).name());
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring unknown preload workflow state '{}'", name);
            }
        }
        if (states.isEmpty()) {
            states.add(WorkflowState.DEFAULT.name());
        }
        return states;
    }

    private static @NonNull String normalizeDomain(@NonNull String domain) {
        return domain.trim().toLowerCase(Locale.ROOT);
    }

    private static @NonNull Map<String, List<Object>> immutableCopy(@NonNull Map<String, List<Object>> source) {
        Map<String, List<Object>> copied = new TreeMap<>();
        source.forEach((key, value) -> copied.put(key, List.copyOf(value)));
        return Map.copyOf(copied);
    }

    public record LoadedMasterAgentRegistry(
            @NonNull List<Object> sharedTools, @NonNull Map<String, List<Object>> domainToolAssignments) {}
}
