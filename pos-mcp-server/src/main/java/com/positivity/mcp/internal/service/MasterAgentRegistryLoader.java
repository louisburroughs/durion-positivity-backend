package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.orchestration.agent.DomainAgentDefinition;
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

    MasterAgentRegistryLoader(@NonNull ToolMetadataRepository repository, @NonNull ApplicationContext applicationContext) {
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
            log.warn("No workflow-scoped tools found for workflowState={}; master agent registry will be empty", workflowState);
        }
        List<Object> sharedTools = new ArrayList<>();
        Map<String, List<Object>> domainTools = new TreeMap<>();
        for (ToolMetadata tool : tools) {
            Object bean = loadToolBean(tool);
            if (bean == null) {
                continue;
            }
            String domain = normalizeDomain(tool.domain());
            if (MASTER_DOMAINS.contains(domain)) {
                sharedTools.add(bean);
                continue;
            }
            domainTools.computeIfAbsent(domain, ignored -> new ArrayList<>()).add(bean);
        }
        List<DomainAgentDefinition> domainAgents = domainTools.entrySet().stream()
                .map(entry -> new DomainAgentDefinition(entry.getKey(), entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new LoadedMasterAgentRegistry(List.copyOf(sharedTools), domainAgents);
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

    public record LoadedMasterAgentRegistry(
            @NonNull List<Object> sharedTools, @NonNull List<DomainAgentDefinition> domainAgents) {}
}
