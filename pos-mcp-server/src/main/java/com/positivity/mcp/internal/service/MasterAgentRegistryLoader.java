package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private static final String DEFAULT_PRELOAD_WORKFLOW_STATE = "IDLE";
    private static final Set<String> MASTER_DOMAINS = Set.of("master", "shared");

    private final ToolMetadataRepository repository;
    private final ApplicationContext applicationContext;
    private final String preloadWorkflowState;

    MasterAgentRegistryLoader(
            @NonNull ToolMetadataRepository repository, @NonNull ApplicationContext applicationContext) {
        this(repository, applicationContext, DEFAULT_PRELOAD_WORKFLOW_STATE);
    }

    @Autowired
    public MasterAgentRegistryLoader(
            @NonNull ToolMetadataRepository repository,
            @NonNull ApplicationContext applicationContext,
            @Value("${mcp.agent.preload-workflow-state:IDLE}") @NonNull String preloadWorkflowState) {
        this.repository = repository;
        this.applicationContext = applicationContext;
        this.preloadWorkflowState = sanitizeWorkflowState(preloadWorkflowState);
    }

    public @NonNull LoadedMasterAgentRegistry loadRegistryDefinition() {
        String workflowState = resolvePreloadWorkflowState();
        List<ToolMetadata> tools = repository.findEnabledByWorkflow(workflowState);
        if (tools.isEmpty()) {
            log.warn(
                    "No workflow-scoped tools found for workflowState={}; master agent registry will be empty",
                    workflowState);
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
        Map<String, List<Object>> roleScopedTools = new TreeMap<>();
        for (String roleName : repository.findAllRoleNames()) {
            List<Object> resolvedRoleTools = new ArrayList<>();
            for (ToolMetadata tool : repository.findEnabledByRoleAndWorkflow(roleName, workflowState)) {
                if (MASTER_DOMAINS.contains(normalizeDomain(tool.domain()))) {
                    continue;
                }
                Object bean = loadToolBean(tool);
                if (bean != null) {
                    resolvedRoleTools.add(bean);
                }
            }
            roleScopedTools.put(normalizeRoleName(roleName), List.copyOf(resolvedRoleTools));
        }
        return new LoadedMasterAgentRegistry(
                List.copyOf(sharedTools), immutableCopy(domainScopedTools), Map.copyOf(roleScopedTools));
    }

    private Object loadToolBean(@NonNull ToolMetadata tool) {
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

    private @NonNull String resolvePreloadWorkflowState() {
        return preloadWorkflowState;
    }

    private static @NonNull String sanitizeWorkflowState(@NonNull String workflowState) {
        String normalized = workflowState.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? DEFAULT_PRELOAD_WORKFLOW_STATE : normalized;
    }

    private static @NonNull String normalizeDomain(@NonNull String domain) {
        return domain.trim().toLowerCase(Locale.ROOT);
    }

    private static @NonNull String normalizeRoleName(@NonNull String roleName) {
        return roleName.trim();
    }

    private static @NonNull Map<String, List<Object>> immutableCopy(@NonNull Map<String, List<Object>> source) {
        Map<String, List<Object>> copied = new TreeMap<>();
        source.forEach((key, value) -> copied.put(key, List.copyOf(value)));
        return Map.copyOf(copied);
    }

    public record LoadedMasterAgentRegistry(
            @NonNull List<Object> sharedTools,
            @NonNull Map<String, List<Object>> domainToolAssignments,
            @NonNull Map<String, List<Object>> roleToolAssignments) {

        public LoadedMasterAgentRegistry(
                @NonNull List<Object> sharedTools, @NonNull Map<String, List<Object>> domainToolAssignments) {
            this(sharedTools, domainToolAssignments, Map.of());
        }
    }
}
