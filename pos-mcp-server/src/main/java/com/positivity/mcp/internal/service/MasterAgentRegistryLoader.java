package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public @NonNull Map<String, List<Object>> loadRoleToolMappings() {
        String workflowState = resolvePreloadWorkflowState();
        List<String> roles = repository.findAllRoleNames();
        if (roles.isEmpty()) {
            log.warn("No roles found in mcp_role table; master agent registry will be empty");
        }
        Map<String, List<Object>> result = new HashMap<>();
        for (String role : roles) {
            result.put(role, loadRoleTools(role, workflowState));
        }
        return result;
    }

    private @NonNull List<Object> loadRoleTools(@NonNull String role, @NonNull String workflowState) {
        List<ToolMetadata> tools = repository.findEnabledByRoleAndWorkflow(role, workflowState);
        List<Object> beans = new ArrayList<>();
        for (ToolMetadata meta : tools) {
            try {
                beans.add(applicationContext.getBean(meta.handlerBean()));
            } catch (NoSuchBeanDefinitionException e) {
                log.warn(
                        "Tool bean '{}' not found for role '{}', skipping: {}",
                        meta.handlerBean(),
                        role,
                        e.getMessage());
            }
        }
        return List.copyOf(beans);
    }

    private @NonNull String resolvePreloadWorkflowState() {
        return preloadWorkflowState;
    }

    private static @NonNull String sanitizeWorkflowState(@NonNull String workflowState) {
        String normalized = workflowState.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? DEFAULT_PRELOAD_WORKFLOW_STATE : normalized;
    }
}
